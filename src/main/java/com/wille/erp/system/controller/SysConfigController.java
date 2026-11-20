package com.wille.erp.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.R;
import com.wille.erp.system.dto.ConfigUpdateRequest;
import com.wille.erp.system.entity.SysConfig;
import com.wille.erp.system.mapper.SysConfigMapper;
import com.wille.erp.system.api.ConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/configs")
@RequiredArgsConstructor
public class SysConfigController {

    private final SysConfigMapper configMapper;
    private final ConfigService configService;

    @GetMapping
    @SaCheckPermission("system:config:list")
    public R<List<SysConfig>> list() {
        return R.ok(configMapper.selectList(Wrappers.<SysConfig>lambdaQuery().orderByAsc(SysConfig::getId)));
    }

    @PutMapping("/{key}")
    @SaCheckPermission("system:config:edit")
    public R<Void> update(@PathVariable String key, @Valid @RequestBody ConfigUpdateRequest request) {
        configService.update(key, request.getValue());
        return R.ok();
    }
}
