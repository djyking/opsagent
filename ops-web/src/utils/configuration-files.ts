import type { ConfigurationField, FileAction } from '@/api/configuration-files';
export const fileActionLabels: Record<FileAction, string> = { PUBLISH_ONLY: '仅发布', PUBLISH_RESTART: '发布并应用', APPLY_ONLY: '应用已发布版本' };
export const fileStatusLabels: Record<string, string> = {
  DRAFT: '草稿待核对', PENDING_APPROVAL: '待审批', APPROVED: '已批准待执行', REJECTED: '已拒绝',
  EXECUTING: '正在执行', EXECUTED: '已执行', QUEUED: '执行排队中', PUBLISHING: '正在发布',
  PUBLISHED_PENDING_APPLY: '已发布待应用', RESTARTING: '正在重启／加载', LOADED_PENDING_VERIFICATION: '已加载待验证',
  VERIFIED: '验证通过', FAILED: '执行失败', PARTIAL_FAILURE: '部分目标失败', RESULT_UNKNOWN: '结果待确认',
  ROLLING_BACK: '正在恢复旧配置', ROLLED_BACK: '已恢复旧版本', PENDING: '待执行', STOPPING: '正在停止',
  STARTING: '正在启动', NOT_STARTED: '尚未启动', AVAILABLE: '已接入', UNAVAILABLE: '暂不可用',
  NOT_REQUESTED: '未请求恢复', RESTORING: '正在恢复', RESTORED: '恢复验证通过',
  FILE_RESTORED_PENDING_VERIFICATION: '原文件已恢复 · 业务待验证',
  FILE_RESTORED_RUNTIME_UNCONFIRMED: '原文件已恢复 · 运行状态待确认',
};
export const terminalFileTasks = new Set(['PUBLISHED_PENDING_APPLY', 'VERIFIED', 'FAILED', 'PARTIAL_FAILURE', 'RESULT_UNKNOWN', 'ROLLED_BACK']);
export function configurationChanges(fields: ConfigurationField[], values: Record<string, unknown>, replaceSecrets: Record<string, boolean>) {
  return fields.filter(field => field.editable && (field.sensitive ? replaceSecrets[field.key] === true : JSON.stringify(values[field.key]) !== JSON.stringify(field.type === 'json' ? JSON.stringify(field.value, null, 2) : field.value)))
    .map(field => {
      const input = values[field.key]; let value = input;
      if (field.type === 'json') { try { value = typeof input === 'string' ? JSON.parse(input) : input; } catch { throw new Error(`${field.label} 不是有效的 JSON`); } }
      if (['integer', 'number'].includes(field.type)) {
        if (input === '' || input == null || !Number.isFinite(Number(input))) throw new Error(`${field.label} 需要有效数值`);
        value = Number(input);
        if (field.type === 'integer' && !Number.isInteger(value)) throw new Error(`${field.label} 需要整数`);
        if (field.min != null && Number(value) < field.min || field.max != null && Number(value) > field.max) throw new Error(`${field.label} 超出允许范围`);
      }
      return { key: field.key, value };
    });
}
