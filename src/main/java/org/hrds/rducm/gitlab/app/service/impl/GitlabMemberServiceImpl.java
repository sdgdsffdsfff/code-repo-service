package org.hrds.rducm.gitlab.app.service.impl;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.annotation.SagaTask;
import io.choerodon.asgard.saga.producer.StartSagaBuilder;
import io.choerodon.asgard.saga.producer.TransactionalProducer;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.mybatis.domain.AuditDomain;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;
import org.hrds.rducm.gitlab.api.controller.dto.GitlabMemberBatchDTO;
import org.hrds.rducm.gitlab.api.controller.dto.GitlabMemberViewDTO;
import org.hrds.rducm.gitlab.api.controller.dto.GitlabMemberUpdateDTO;
import org.hrds.rducm.gitlab.app.service.GitlabMemberService;
import org.hrds.rducm.gitlab.domain.entity.GitlabMember;
import org.hrds.rducm.gitlab.domain.entity.GitlabRepository;
import org.hrds.rducm.gitlab.domain.entity.GitlabUser;
import org.hrds.rducm.gitlab.domain.repository.GitlabMemberRepository;
import org.hrds.rducm.gitlab.domain.repository.GitlabRepositoryRepository;
import org.hrds.rducm.gitlab.domain.repository.GitlabUserRepository;
import org.hrds.rducm.gitlab.infra.constant.Constants;
import org.hrds.rducm.gitlab.infra.util.ConvertUtils;
import org.hzero.core.base.AopProxy;
import org.hzero.mybatis.domian.Condition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.hrds.rducm.gitlab.app.eventhandler.constants.SagaTopicCodeConstants.RDUCM_ADD_MEMBERS;

@Service
public class GitlabMemberServiceImpl implements GitlabMemberService, AopProxy<GitlabMemberServiceImpl> {
    private final GitlabMemberRepository gitlabMemberRepository;

    @Autowired
    private GitlabRepositoryRepository gitlabRepositoryRepository;

    @Autowired
    private GitlabUserRepository gitlabUserRepository;

    @Autowired
    private TransactionalProducer producer;

    public GitlabMemberServiceImpl(GitlabMemberRepository gitlabMemberRepository) {
        this.gitlabMemberRepository = gitlabMemberRepository;
    }

    @Override
    public Page<GitlabMemberViewDTO> list(Long projectId, PageRequest pageRequest) {
        GitlabMember query = new GitlabMember();
        query.setProjectId(projectId);
        Page<GitlabMember> page = PageHelper.doPage(pageRequest, () -> gitlabMemberRepository.select(query));
        return ConvertUtils.convertPage(page, GitlabMemberViewDTO.class);
    }

    /**
     * 批量新增或修改成员
     * @param projectId
     * @param gitlabMembersDTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchAddOrUpdateMembers(Long projectId, GitlabMemberBatchDTO gitlabMemberBatchDTO) {
        // <0> 校验入参 + 转换
        List<GitlabMember> gitlabMembers = convertGitlabMemberBatchDTO(projectId, gitlabMemberBatchDTO);

        // <1> 数据库添加成员, 已存在需要更新
        gitlabMemberRepository.batchAddOrUpdateMembersBefore(gitlabMembers);

        // <2> 调用gitlab api添加成员 todo 事务一致性问题
        gitlabMemberRepository.batchAddOrUpdateMembersToGitlab(gitlabMembers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMember(Long projectId, Long repositoryId, Long memberId, GitlabMemberUpdateDTO gitlabMemberUpdateDTO) {
        // <0> 校验入参 todo + 转换
        final GitlabMember gitlabMember = ConvertUtils.convertObject(gitlabMemberUpdateDTO, GitlabMember.class);
        gitlabMember.setId(memberId);
        gitlabMember.setProjectId(projectId);
        gitlabMember.setRepositoryId(repositoryId);

        // 获取gitlab项目id和用户id todo 应从外部接口获取, 暂时从数据库获取
        GitlabMember dbMember = gitlabMemberRepository.selectByPrimaryKey(memberId);
        gitlabMemberRepository.checkIsSyncGitlab(dbMember);
        gitlabMember.setGlProjectId(dbMember.getGlProjectId());
        gitlabMember.setGlUserId(dbMember.getGlUserId());

        // <1> 数据库更新成员
        gitlabMemberRepository.updateMemberBefore(gitlabMember);

        // <2> 调用gitlab api更新成员 todo 事务一致性问题
        gitlabMemberRepository.updateMemberToGitlab(gitlabMember);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long projectId, Long repositoryId, Long memberId) {
        // <1> 数据库删除成员
        GitlabMember gitlabMember = gitlabMemberRepository.selectByPrimaryKey(memberId);
        gitlabMemberRepository.deleteByPrimaryKey(memberId);

        // <2> 调用gitlab api删除成员 todo 事务一致性问题
        gitlabMemberRepository.removeMemberToGitlab(gitlabMember.getGlProjectId(), gitlabMember.getGlUserId());
    }

    /**
     * 将成员设为过期
     */
    private void batchExpireMembers(List<GitlabMember> expiredGitlabMembers) {
        // <2> 设置过期成员的状态
        expiredGitlabMembers.forEach(m -> {
            m.setState(Constants.MemberState.EXPIRED);
        });

        // <3> 批量更新
        gitlabMemberRepository.batchUpdateByPrimaryKeySelective(expiredGitlabMembers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleExpiredMembers() {
        // <1> 查询已过期的成员
        Condition condition = new Condition(GitlabMember.class);
        condition.createCriteria().andLessThanOrEqualTo("glExpiresAt", new Date());
        List<GitlabMember> expiredGitlabMembers = gitlabMemberRepository.selectByCondition(condition);

        // <2> 设置过期成员的状态
        batchExpireMembers(expiredGitlabMembers);
    }

    @Saga(code = RDUCM_ADD_MEMBERS, description = "批量添加代码库成员")
    @Transactional(rollbackFor = Exception.class)
    public void batchAddMemberSagaDemo(List<GitlabMember> gitlabMembers) {
        // <1> 数据库添加成员
        gitlabMemberRepository.batchInsertSelective(gitlabMembers);

        // 创建saga
        producer.apply(
                StartSagaBuilder.newBuilder()
                        .withLevel(ResourceLevel.PROJECT)
                        .withRefType("app-service")
                        .withSagaCode(RDUCM_ADD_MEMBERS)
                        .withPayloadAndSerialize(null)
                        .withRefId(null)
                        .withSourceId(null),
                builder -> {});
    }

    @SagaTask(code = "", sagaCode = RDUCM_ADD_MEMBERS, description = "调用gitlab api添加成员并回写", seq = 1)
    public void batchAddMemberToGitlabSagaDemo(String payload) {
        List<GitlabMember> gitlabMembers = new ArrayList<>();
        // <2> 调用gitlab api添加成员
        gitlabMemberRepository.batchAddOrUpdateMembersToGitlab(gitlabMembers);
    }

    /**
     * 将GitlabMemberBatchDTO转换为List<GitlabMember>
     * @param gitlabMemberBatchDTO
     * @return
     */
    private List<GitlabMember> convertGitlabMemberBatchDTO(Long projectId, GitlabMemberBatchDTO gitlabMemberBatchDTO) {
        // 查询gitlab项目id和用户id todo 应从外部接口获取, 暂时从数据库获取
        Map<Long, Integer> repositoryIdToGlProjectIdMap = new HashMap<>();
        gitlabMemberBatchDTO.getRepositoryIds().forEach(repositoryId -> {
            // 获取gitlab项目id
            GitlabRepository gitlabRepository = gitlabRepositoryRepository.selectOne(new GitlabRepository().setRepositoryId(repositoryId));
            repositoryIdToGlProjectIdMap.put(repositoryId, gitlabRepository.getGlProjectId());
        });

        // 查询gitlab用户id todo 应从外部接口获取, 暂时从数据库获取
        Map<Long, Integer> userIdToGlUserIdMap = new HashMap<>();
        gitlabMemberBatchDTO.getMembers().forEach(m -> {
            GitlabUser gitlabUser = gitlabUserRepository.selectOne(new GitlabUser().setUserId(m.getUserId()));
            userIdToGlUserIdMap.put(m.getUserId(), gitlabUser.getGlUserId());
        });

        // 转换为List<GitlabMember>格式
        List<GitlabMember> gitlabMembers = new ArrayList<>();
        for (Long repositoryId : gitlabMemberBatchDTO.getRepositoryIds()) {
            for (GitlabMemberBatchDTO.GitlabMemberCreateDTO member : gitlabMemberBatchDTO.getMembers()) {
                GitlabMember gitlabMember = ConvertUtils.convertObject(member, GitlabMember.class);
                gitlabMember.setProjectId(projectId);
                gitlabMember.setRepositoryId(repositoryId);

                // 设置gitlab项目id和用户id
                gitlabMember.setGlProjectId(repositoryIdToGlProjectIdMap.get(repositoryId));
                gitlabMember.setGlUserId(userIdToGlUserIdMap.get(member.getUserId()));

                gitlabMembers.add(gitlabMember);
            }
        }

        return gitlabMembers;
    }
}
