package com.opsagent.auth;

import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * 使用安全随机数生成图形验证码，答案只在认证服务内部短暂使用。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class CaptchaImageGenerator {
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();

    Generated generate() {
        StringBuilder answer = new StringBuilder(5);
        for (int index = 0; index < 5; index++) {
            answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        BufferedImage image = new BufferedImage(192, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setPaint(new GradientPaint(0, 0, new Color(235, 244, 255), 192, 64, new Color(232, 250, 246)));
            canvas.fillRect(0, 0, 192, 64);
            for (int index = 0; index < 32; index++) {
                canvas.setColor(new Color(140 + random.nextInt(70), 170 + random.nextInt(60), 200));
                canvas.fillOval(random.nextInt(192), random.nextInt(64), 2, 2);
            }
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            for (int index = 0; index < answer.length(); index++) {
                AffineTransform original = canvas.getTransform();
                int x = 15 + index * 34;
                int y = 44 + random.nextInt(7) - 3;
                canvas.rotate((random.nextDouble() - 0.5) * 0.32, x + 13, 32);
                canvas.setColor(new Color(35 + random.nextInt(30), 65 + random.nextInt(45), 115 + random.nextInt(40)));
                canvas.drawString(answer.substring(index, index + 1), x, y);
                canvas.setTransform(original);
            }
            canvas.setStroke(new BasicStroke(1.2f));
            for (int index = 0; index < 3; index++) {
                canvas.setColor(new Color(94, 141 + random.nextInt(40), 175));
                canvas.drawLine(0, random.nextInt(64), 192, random.nextInt(64));
            }
        } finally {
            canvas.dispose();
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return new Generated(answer.toString(), "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("验证码图片生成失败", exception);
        }
    }

    /**
     * 内部生成结果，不用作控制器响应。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Generated(String answer, String imageDataUrl) {}
}
