package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Stores immutable proposals, never approval decisions or a second publication history.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Repository
class ConfigurationProposalRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ConfigurationProposalRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    ConfigurationProposalDtos.Proposal existing(
            String requestId, long ownerId, String requestHash) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM operations_managed_config_proposal WHERE request_id=?",
                        requestId);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        if (((Number) row.get("owner_id")).longValue() != ownerId
                || !requestHash.equals(row.get("request_hash")))
            throw conflict("请求ID已用于其他提案，不允许替换内容。");
        return read(row);
    }

    ConfigurationProposalDtos.Proposal create(
            ConfigurationProposalDtos.Proposal proposal, String requestId, String requestHash) {
        try {
            jdbc.update(
                    "INSERT INTO"
                            + " operations_managed_config_proposal(proposal_id,request_id,"
                            + "request_hash,owner_id,immutable_digest,proposal_json,expires_at)"
                            + " VALUES(?,?,?,?,?,?,?)",
                    proposal.proposalId(),
                    requestId,
                    requestHash,
                    proposal.ownerId(),
                    proposal.immutableDigest(),
                    write(proposal),
                    Timestamp.from(proposal.expiresAt()));
            return proposal;
        } catch (DuplicateKeyException duplicate) {
            return existing(requestId, proposal.ownerId(), requestHash);
        }
    }

    ConfigurationProposalDtos.Proposal get(String id, long ownerId) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM operations_managed_config_proposal WHERE proposal_id=? AND"
                                + " owner_id=?",
                        id,
                        ownerId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "变更提案不存在或不属于当前账号。");
        return read(rows.get(0));
    }

    void bindRun(String proposalId, String digest, String runId, long ownerId) {
        int changed =
                jdbc.update(
                        "UPDATE operations_managed_config_proposal SET run_id=? WHERE proposal_id=?"
                                + " AND owner_id=? AND immutable_digest=? AND (run_id IS NULL OR"
                                + " run_id=?)",
                        runId,
                        proposalId,
                        ownerId,
                        digest,
                        runId);
        if (changed == 0) throw conflict("此提案已绑定其他运行或摘要不匹配。");
    }

    private ConfigurationProposalDtos.Proposal read(Map<String, Object> row) {
        try {
            var proposal =
                    json.readValue(
                            row.get("proposal_json").toString(),
                            ConfigurationProposalDtos.Proposal.class);
            var material =
                    new ConfigurationProposalDtos.Proposal(
                            proposal.proposalId(),
                            "",
                            proposal.configurationId(),
                            proposal.targetCode(),
                            proposal.catalogId(),
                            proposal.identity(),
                            proposal.expectedRevision(),
                            proposal.before(),
                            proposal.desired(),
                            proposal.changes(),
                            proposal.action(),
                            proposal.rollbackVersionId(),
                            proposal.createdAt(),
                            proposal.expiresAt(),
                            proposal.status(),
                            proposal.targetSnapshot(),
                            proposal.comment(),
                            proposal.ownerId());
            if (!proposal.immutableDigest().equals(row.get("immutable_digest"))
                    || !proposal.immutableDigest()
                            .equals(
                                    ManagedConfigurationRepository.hash(
                                            json.writeValueAsString(material))))
                throw new IllegalStateException("CONFIG_PROPOSAL_DIGEST_MISMATCH");
            return proposal;
        } catch (Exception failure) {
            throw new IllegalStateException("CONFIG_PROPOSAL_INVALID");
        }
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("CONFIG_PROPOSAL_SERIALIZATION_FAILED");
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
