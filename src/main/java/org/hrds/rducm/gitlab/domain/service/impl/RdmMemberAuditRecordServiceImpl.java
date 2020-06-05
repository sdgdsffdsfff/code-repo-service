package org.hrds.rducm.gitlab.domain.service.impl;

import io.choerodon.core.domain.Page;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import org.gitlab4j.api.models.Member;
import org.gitlab4j.api.models.Project;
import org.hrds.rducm.gitlab.api.controller.dto.MemberAuditRecordQueryDTO;
import org.hrds.rducm.gitlab.api.controller.dto.RdmMemberAuditRecordViewDTO;
import org.hrds.rducm.gitlab.app.assembler.RdmMemberAuditRecordAssembler;
import org.hrds.rducm.gitlab.domain.entity.RdmMember;
import org.hrds.rducm.gitlab.domain.entity.RdmMemberAuditRecord;
import org.hrds.rducm.gitlab.domain.facade.C7nBaseServiceFacade;
import org.hrds.rducm.gitlab.domain.facade.C7nDevOpsServiceFacade;
import org.hrds.rducm.gitlab.domain.repository.RdmMemberAuditRecordRepository;
import org.hrds.rducm.gitlab.domain.repository.RdmMemberRepository;
import org.hrds.rducm.gitlab.domain.service.IRdmMemberAuditRecordService;
import org.hrds.rducm.gitlab.infra.client.gitlab.api.admin.GitlabAdminApi;
import org.hzero.mybatis.domian.Condition;
import org.hzero.mybatis.util.Sqls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 安全审计
 *
 * @author ying.xie@hand-china.com
 * @date 2020/3/3
 */
@Service
public class RdmMemberAuditRecordServiceImpl implements IRdmMemberAuditRecordService {
    public static final Logger LOGGER = LoggerFactory.getLogger(RdmMemberAuditRecordServiceImpl.class);
    @Autowired
    private RdmMemberAuditRecordRepository rdmMemberAuditRecordRepository;
    @Autowired
    private RdmMemberRepository memberRepository;
    @Autowired
    private GitlabAdminApi gitlabAdminApi;
    @Autowired
    private C7nDevOpsServiceFacade c7NDevOpsServiceFacade;
    @Autowired
    private C7nBaseServiceFacade c7NBaseServiceFacade;
    @Autowired
    private RdmMemberAuditRecordAssembler rdmMemberAuditRecordAssembler;

    @Override
    public Page<RdmMemberAuditRecordViewDTO> pageByOptions(Long organizationId,
                                                           Set<Long> projectIds,
                                                           Set<Long> repositoryIds,
                                                           PageRequest pageRequest,
                                                           MemberAuditRecordQueryDTO queryDTO, ResourceLevel resourceLevel) {
        String repositoryName = queryDTO.getRepositoryName();

        Condition condition = Condition.builder(RdmMemberAuditRecord.class)
                .where(Sqls.custom()
                        .andEqualTo(RdmMemberAuditRecord.FIELD_SYNC_FLAG, false)
                        .andIn(RdmMemberAuditRecord.FIELD_ORGANIZATION_ID, Collections.singleton(organizationId))
                        .andIn(RdmMemberAuditRecord.FIELD_PROJECT_ID, projectIds, true)
                        .andIn(RdmMemberAuditRecord.FIELD_REPOSITORY_ID, repositoryIds, true))
                .build();


        switch (resourceLevel) {
            case ORGANIZATION: {
                // 调用外部接口模糊查询 应用服务
                if (!StringUtils.isEmpty(repositoryName)) {
                    Set<Long> repositoryIdSet = c7NDevOpsServiceFacade.listC7nAppServiceIdsByNameOnOrgLevel(organizationId, repositoryName);

                    if (repositoryIdSet.isEmpty()) {
                        return new Page<>();
                    }

                    condition.and().andIn(RdmMemberAuditRecord.FIELD_REPOSITORY_ID, repositoryIdSet);
                }
                break;
            }
            case PROJECT: {
                // 调用外部接口模糊查询 应用服务
                if (!StringUtils.isEmpty(repositoryName)) {
                    Set<Long> repositoryIdSet = c7NDevOpsServiceFacade.listC7nAppServiceIdsByNameOnProjectLevel(projectIds.iterator().next(), repositoryName);

                    if (repositoryIdSet.isEmpty()) {
                        return new Page<>();
                    }

                    condition.and().andIn(RdmMemberAuditRecord.FIELD_REPOSITORY_ID, repositoryIdSet);
                }
                break;
            }
            default:
                break;
        }

        Page<RdmMemberAuditRecord> page = PageHelper.doPageAndSort(pageRequest, () -> rdmMemberAuditRecordRepository.selectByCondition(condition));

        return rdmMemberAuditRecordAssembler.pageToViewDTO(page, resourceLevel);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RdmMemberAuditRecord> batchCompare(Long organizationId) {
        StopWatch stopWatch = new StopWatch();

        // <0> 删除原有数据
        rdmMemberAuditRecordRepository.delete(new RdmMemberAuditRecord().setOrganizationId(organizationId));

        // <1> 对比组织所有成员
        stopWatch.start("任务1");
        List<RdmMemberAuditRecord> list = compareMembersByOrganizationId(organizationId);
        stopWatch.stop();

        // <2> 批量插入数据库
        // 优化, 每次最多一次性插入10000条
        stopWatch.start("任务2");
        int maxInsert = 10000;
        int curIndex = 0;
        while (curIndex < list.size()) {
            rdmMemberAuditRecordRepository.batchInsertCustom(list.subList(curIndex, Math.min(curIndex + maxInsert, list.size())));
            curIndex += maxInsert;
        }
        stopWatch.stop();

        LOGGER.info("执行时长:{}, 执行详情\n{}", stopWatch.getTotalTimeMillis(), stopWatch.prettyPrint());

        return list;
    }

    private List<RdmMemberAuditRecord> compareMembersByOrganizationId(Long organizationId) {
        // <1> 获取组织下所有项目
        Set<Long> projectIds = c7NBaseServiceFacade.listProjectIds(organizationId);

        List<RdmMemberAuditRecord> list = projectIds.stream()
                .map(projectId -> {
                    return compareMembersByProjectId(organizationId, projectId);
                })
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        return list;
    }

    private List<RdmMemberAuditRecord> compareMembersByProjectId(Long organizationId,
                                                                 Long projectId) {
        // 获取项目下所有代码库id和Gitlab项目id
        Map<Long, Long> appServiceIdMap = c7NDevOpsServiceFacade.listC7nAppServiceIdsMapOnProjectLevel(projectId);


        List<RdmMemberAuditRecord> list = appServiceIdMap.entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .map((entry) -> {
                    Long repositoryId = entry.getKey();
                    Integer glProjectId = Math.toIntExact(entry.getValue());
                    return compareMembersByRepositoryId(organizationId, projectId, repositoryId, glProjectId);
                })
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        return list;
    }

    /**
     * 审计一个代码库的成员权限
     *
     * @param organizationId
     * @param projectId
     * @param repositoryId
     * @param glProjectId
     * @return
     */
    private List<RdmMemberAuditRecord> compareMembersByRepositoryId(Long organizationId,
                                                                    Long projectId,
                                                                    Long repositoryId,
                                                                    Integer glProjectId) {
        // 查询gitlab所有成员

        // 判断一下gitlab是否存在该仓库, 避免报错
        Project project = gitlabAdminApi.getProject(glProjectId);
        if (project == null) {
            return Collections.emptyList();
        }

        List<Member> members = gitlabAdminApi.getAllMembers(glProjectId);
        LOGGER.info("{}项目查询到成员数量为:{}", glProjectId, members.size());

        // 查询数据库所有成员
        List<RdmMember> dbMembers = memberRepository.select(new RdmMember().setGlProjectId(glProjectId));

        return compareMembersAndReturnAudit(organizationId, projectId, repositoryId, glProjectId, dbMembers, members);
    }

    /**
     * 比较数据库成员和Gitlab成员差异, 并返回审计结果
     *
     * @param organizationId
     * @param projectId
     * @param repositoryId
     * @param glProjectId
     * @param dbMembers
     * @param glMembers
     * @return 差异数据的列表
     */
    private List<RdmMemberAuditRecord> compareMembersAndReturnAudit(Long organizationId,
                                                                    Long projectId,
                                                                    Long repositoryId,
                                                                    Integer glProjectId,
                                                                    List<RdmMember> dbMembers,
                                                                    List<Member> glMembers) {
        Map<Integer, RdmMember> dbMemberMap = dbMembers.stream().collect(Collectors.toMap(RdmMember::getGlUserId, m -> m));

        // 比较是否有差异
        List<RdmMemberAuditRecord> memberAudits = new ArrayList<>();
        for (Member member : glMembers) {
            boolean isDifferent = false;

            // 查找数据库是否有此成员
            RdmMember dbMember = dbMemberMap.get(member.getId());

            // 移除
            dbMemberMap.remove(member.getId());
            if (dbMember == null) {
                // 数据库未找到该成员, 说明不一致
                isDifferent = true;
            } else {
                if (!Objects.equals(member.getAccessLevel().toValue(), dbMember.getGlAccessLevel())) {
                    // 如果AccessLevel不相等, 说明不一致
                    isDifferent = true;
                }

                if (!Objects.equals(member.getExpiresAt(), dbMember.getGlExpiresAt())) {
                    // 如果ExpiresAt不相等, 说明不一致
                    isDifferent = true;
                }
            }

            if (isDifferent) {
                memberAudits.add(buildMemberAudit(organizationId, projectId, repositoryId, glProjectId, member, dbMember));
            }
        }

        // 如果dbMemberMap还有数据, 说明不一致
        if (!dbMemberMap.isEmpty()) {
            dbMemberMap.forEach((k, v) -> {
                memberAudits.add(buildMemberAudit(organizationId, projectId, repositoryId, glProjectId, null, v));
            });
        }

        return memberAudits;
    }

    private RdmMemberAuditRecord buildMemberAudit(Long organizationId,
                                                  Long projectId,
                                                  Long repositoryId,
                                                  Integer glProjectId,
                                                  Member glMember,
                                                  RdmMember dbMember) {
        RdmMemberAuditRecord memberAudit = new RdmMemberAuditRecord()
                .setOrganizationId(organizationId)
                .setProjectId(projectId)
                .setRepositoryId(repositoryId)
                .setGlProjectId(glProjectId);

        if (glMember != null) {
            memberAudit.setGlUserId(glMember.getId())
                    .setGlAccessLevel(glMember.getAccessLevel().toValue())
                    .setGlExpiresAt(glMember.getExpiresAt());
        }

        if (dbMember != null) {
            memberAudit.setUserId(dbMember.getUserId())
                    .setAccessLevel(dbMember.getGlAccessLevel())
                    .setExpiresAt(dbMember.getGlExpiresAt());
        }

        return memberAudit;
    }
}
