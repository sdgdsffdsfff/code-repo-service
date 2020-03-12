package org.hrds.rducm.gitlab.api.controller.dto.member;

import io.swagger.annotations.ApiModelProperty;
import springfox.documentation.annotations.ApiIgnore;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * @author ying.xie@hand-china.com
 * @date 2020/3/11
 */
public class MemberApprovalCreateDTO {
    @ApiModelProperty(value = "代码库id", required = true)
    @NotNull
    private Long repositoryId;

    @ApiModelProperty(value = "申请人，用户id", required = true)
    @NotNull
    private Long applicantUserId;

    @ApiModelProperty(value = "申请类型（新成员|权限变更）", required = true)
    @NotBlank
    private String applicantType;

    @ApiModelProperty(value = "权限等级", required = true)
    @NotNull
    private Integer accessLevel;

    @ApiModelProperty(value = "旧权限等级", hidden = true)
    private Integer oldAccessLevel;

    public Long getRepositoryId() {
        return repositoryId;
    }

    public MemberApprovalCreateDTO setRepositoryId(Long repositoryId) {
        this.repositoryId = repositoryId;
        return this;
    }

    public Long getApplicantUserId() {
        return applicantUserId;
    }

    public MemberApprovalCreateDTO setApplicantUserId(Long applicantUserId) {
        this.applicantUserId = applicantUserId;
        return this;
    }

    public String getApplicantType() {
        return applicantType;
    }

    public MemberApprovalCreateDTO setApplicantType(String applicantType) {
        this.applicantType = applicantType;
        return this;
    }

    public Integer getAccessLevel() {
        return accessLevel;
    }

    public MemberApprovalCreateDTO setAccessLevel(Integer accessLevel) {
        this.accessLevel = accessLevel;
        return this;
    }

    public Integer getOldAccessLevel() {
        return oldAccessLevel;
    }

    public MemberApprovalCreateDTO setOldAccessLevel(Integer oldAccessLevel) {
        this.oldAccessLevel = oldAccessLevel;
        return this;
    }
}
