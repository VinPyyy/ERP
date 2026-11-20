package com.wille.erp.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.common.api.R;
import com.wille.erp.system.entity.SysDocNoRule;
import com.wille.erp.system.mapper.SysDocNoRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 编号规则管理：currentSeq/currentPeriod 只读（取号专用），可改 prefix/datePattern/seqLength/seqScope。
 */
@RestController
@RequestMapping("/api/system/doc-no-rules")
@RequiredArgsConstructor
public class SysDocNoRuleController {

    private final SysDocNoRuleMapper docNoRuleMapper;

    @GetMapping
    @SaCheckPermission("system:docno:list")
    public R<List<SysDocNoRule>> list() {
        return R.ok(docNoRuleMapper.selectList(
                Wrappers.<SysDocNoRule>lambdaQuery().orderByAsc(SysDocNoRule::getId)));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:docno:edit")
    public R<Void> update(@PathVariable Long id, @RequestBody SysDocNoRule request) {
        SysDocNoRule rule = docNoRuleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "编号规则不存在");
        }
        rule.setPrefix(request.getPrefix());
        rule.setDatePattern(request.getDatePattern());
        rule.setSeqLength(request.getSeqLength());
        rule.setSeqScope(request.getSeqScope());
        docNoRuleMapper.updateById(rule);
        return R.ok();
    }
}
