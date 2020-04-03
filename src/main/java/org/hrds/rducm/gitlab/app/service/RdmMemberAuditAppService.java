package org.hrds.rducm.gitlab.app.service;

/**
 * 成员权限审计应用服务
 *
 * @author ying.xie@hand-china.com 2020-04-02 14:57:44
 */
public interface RdmMemberAuditAppService {
    /**
     * 根据策略进行同步
     *
     * @param id
     * @param syncStrategy @param syncStrategy 同步策略
     *                     1 表示 当前 -> Gitlab
     *                     2 表示 当前 <- Gitlab
     */
    void syncByStrategy(Long id, int syncStrategy);
}
