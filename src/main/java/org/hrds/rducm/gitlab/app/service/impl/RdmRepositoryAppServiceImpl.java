package org.hrds.rducm.gitlab.app.service.impl;

import org.hrds.rducm.gitlab.api.controller.dto.base.BaseC7nAppServiceViewDTO;
import org.hrds.rducm.gitlab.app.service.RdmRepositoryAppService;
import org.hrds.rducm.gitlab.domain.facade.IC7nDevOpsServiceFacade;
import org.hrds.rducm.gitlab.infra.feign.vo.C7nAppServiceVO;
import org.hrds.rducm.gitlab.infra.util.ConvertUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用服务默认实现
 *
 * @author ying.xie@hand-china.com 2020-02-26 14:03:22
 */
@Service
public class RdmRepositoryAppServiceImpl implements RdmRepositoryAppService {
    @Autowired
    private IC7nDevOpsServiceFacade ic7nDevOpsServiceFacade;

    /**
     * 查询所有[已启用]的服务
     *
     * @return
     */
    @Override
    public List<BaseC7nAppServiceViewDTO> listByActive(Long projectId) {
        List<C7nAppServiceVO> appServiceVOS = ic7nDevOpsServiceFacade.listAppServiceByActive(projectId);

        return ConvertUtils.convertList(appServiceVOS, BaseC7nAppServiceViewDTO::convert);
    }
}
