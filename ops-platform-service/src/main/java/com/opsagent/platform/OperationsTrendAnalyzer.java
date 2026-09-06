package com.opsagent.platform;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 以有界时间窗口内的真实样本做最小二乘趋势估计；缺样本、过期或低拟合度不生成预测数字。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class OperationsTrendAnalyzer {
    private static final String METHOD = "最近窗口最小二乘线性拟合，外推15分钟；至少8个样本、15分钟跨度，"
            + "R²≥0.35且最近样本不超过3分钟。预测用于风险提示，不代表故障必然发生。";

    OperationsDtos.Metric analyze(
            String id, String label, String job, List<OperationsDtos.Point> input, double threshold, Instant now) {
        List<OperationsDtos.Point> points = input.stream()
                .filter(point -> Double.isFinite(point.value()) && point.value() >= 0 && point.value() <= 100)
                .filter(point -> !point.timestamp().isAfter(now.plusSeconds(30)))
                .sorted(Comparator.comparing(OperationsDtos.Point::timestamp)).toList();
        if (points.isEmpty()) return unknown(id, label, job, "没有可用时序样本；未接入该指标或窗口内没有请求。");
        OperationsDtos.Point last = points.get(points.size() - 1);
        if (Duration.between(last.timestamp(), now).getSeconds() > 180) {
            return new OperationsDtos.Metric(id, label, job, "%", last.value(), null, null, points.size(),
                    "UNKNOWN", METHOD, "最近样本已过期，不能据此判断当前状态。", last.timestamp(), points);
        }
        double spanMinutes = Duration.between(points.get(0).timestamp(), last.timestamp()).toSeconds() / 60.0;
        String reason = "样本跨度不足15分钟或样本数不足8，暂不外推。";
        Double forecast = null;
        Double slope = null;
        if (points.size() >= 8 && spanMinutes >= 15) {
            double meanX = 0;
            double meanY = 0;
            Instant first = points.get(0).timestamp();
            for (var point : points) {
                meanX += Duration.between(first, point.timestamp()).toSeconds() / 60.0;
                meanY += point.value();
            }
            meanX /= points.size();
            meanY /= points.size();
            double covariance = 0;
            double varianceX = 0;
            double varianceY = 0;
            for (var point : points) {
                double x = Duration.between(first, point.timestamp()).toSeconds() / 60.0 - meanX;
                double y = point.value() - meanY;
                covariance += x * y;
                varianceX += x * x;
                varianceY += y * y;
            }
            slope = varianceX == 0 ? 0 : covariance / varianceX;
            double fit = varianceY < 0.0001 ? 1 : covariance * covariance / (varianceX * varianceY);
            double largestGap = 0;
            for (int i = 1; i < points.size(); i++) {
                largestGap = Math.max(largestGap,
                        Duration.between(points.get(i - 1).timestamp(), points.get(i).timestamp()).toSeconds());
            }
            if (fit >= 0.35 && largestGap <= Math.max(180, spanMinutes * 60 / points.size() * 3)) {
                forecast = round(Math.max(0, Math.min(100, meanY + slope * (spanMinutes + 15 - meanX))));
                reason = String.format(Locale.ROOT, "拟合R²=%.2f；阈值%.1f%%，预计15分钟后%.1f%%。", fit, threshold, forecast);
            } else {
                reason = "样本波动较大或存在采集空档，线性趋势不足以支持预测；只展示当前观测。";
            }
        }
        boolean risk = last.value() >= threshold || (forecast != null && forecast >= threshold && slope > 0);
        return new OperationsDtos.Metric(id, label, job, "%", round(last.value()), forecast,
                slope == null ? null : round(slope), points.size(), risk ? "RISK" : "OK", METHOD,
                reason, last.timestamp(), points);
    }

    OperationsDtos.Metric unknown(String id, String label, String job, String reason) {
        return new OperationsDtos.Metric(id, label, job, "%", null, null, null, 0, "UNKNOWN", METHOD,
                reason, null, List.of());
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
