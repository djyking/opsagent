package com.opsagent.ticket;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 工单聚合的核心持久化实体。
 *
 * @author heyu
 * @since 2026/8/8
 */
@TableName("ticket")
public class Ticket {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketNo;
    private String title;
    private String description;
    private String priority;
    private String status;
    private Long creatorId;
    private Long assigneeId;
    private String affectedCiCode;
    private String sourceType;
    private String environment;
    private Long ownerActorId;
    private String incidentId;
    private String episodeId;
    private Boolean publicDemo;
    @Version private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String v) {
        ticketNo = v;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String v) {
        title = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String v) {
        priority = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long v) {
        creatorId = v;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long v) {
        assigneeId = v;
    }

    public String getAffectedCiCode() {
        return affectedCiCode;
    }

    public void setAffectedCiCode(String affectedCiCode) {
        this.affectedCiCode = affectedCiCode;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getVersion() {
        return version;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String value) {
        environment = value;
    }

    public Long getOwnerActorId() {
        return ownerActorId;
    }

    public void setOwnerActorId(Long value) {
        ownerActorId = value;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(String value) {
        incidentId = value;
    }

    public String getEpisodeId() {
        return episodeId;
    }

    public void setEpisodeId(String value) {
        episodeId = value;
    }

    public Boolean getPublicDemo() {
        return publicDemo;
    }

    public void setPublicDemo(Boolean value) {
        publicDemo = value;
    }

    public void setVersion(Integer v) {
        version = v;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setDeleted(Integer v) {
        deleted = v;
    }
}
