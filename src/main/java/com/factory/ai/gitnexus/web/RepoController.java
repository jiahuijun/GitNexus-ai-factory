package com.factory.ai.gitnexus.web;

import com.factory.ai.gitnexus.GitNexusClient;
import com.factory.ai.gitnexus.dto.RepoInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库列表 REST 控制器。
 *
 * <p>暴露 {@code GET /repos} 端点，从 GitNexus 动态获取已索引仓库列表，
 * 供前端渲染仓库选择下拉框。</p>
 */
@RestController
@RequestMapping("/repos")
public class RepoController {

    private final GitNexusClient gitNexus;

    public RepoController(GitNexusClient gitNexus) {
        this.gitNexus = gitNexus;
    }

    /**
     * 列出所有已索引的仓库。
     *
     * @return 200 OK + 仓库列表（含 name 和 path）
     */
    @GetMapping
    public ResponseEntity<List<RepoInfo>> listRepos() {
        return ResponseEntity.ok(gitNexus.listRepos());
    }
}
