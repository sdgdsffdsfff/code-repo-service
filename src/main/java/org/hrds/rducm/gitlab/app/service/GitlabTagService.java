package org.hrds.rducm.gitlab.app.service;

import org.gitlab4j.api.models.ProtectedBranch;
import org.gitlab4j.api.models.ProtectedTag;
import org.gitlab4j.api.models.Tag;

import java.util.List;

public interface GitlabTagService {

    List<Tag> getTags(Long repositoryId);

    List<ProtectedTag> getProtectedTags(Long repositoryId);

    ProtectedTag protectTag(Long repositoryId,
                            String glTagName,
                            Integer glCreateAccessLevel);

    void unprotectTag(Long repositoryId, String glTagName);
}
