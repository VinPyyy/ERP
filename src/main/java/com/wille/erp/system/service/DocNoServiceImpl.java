package com.wille.erp.system.service;

import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.api.DocNoService;
import com.wille.erp.system.entity.SysDocNoRule;
import com.wille.erp.system.mapper.SysDocNoRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 单据编号：{prefix}-{datePattern 日期}-{seqLength 位流水}，如 XSDD-20261119-0001。
 * 取号 = 事务内 SELECT FOR UPDATE 锁规则行 → 期间变更重置 → 自增 → 回写。
 * 并发下由行锁串行化（DocNoServiceTest 50 线程验证无重号）；作废不回收，跳号正常。
 */
@Service
@RequiredArgsConstructor
public class DocNoServiceImpl implements DocNoService {

    private final SysDocNoRuleMapper docNoRuleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String nextNo(DocType docType) {
        SysDocNoRule rule = docNoRuleMapper.selectForUpdateByDocType(docType.name());
        if (rule == null) {
            throw new BizException(ErrorCode.DOC_NO_FAIL, "编号规则不存在: " + docType);
        }

        String period = currentPeriod(rule.getDatePattern());
        if (!Objects.equals(period, rule.getCurrentPeriod())) {
            rule.setCurrentPeriod(period);
            rule.setCurrentSeq(0L);
        }
        long seq = (rule.getCurrentSeq() == null ? 0L : rule.getCurrentSeq()) + 1;
        rule.setCurrentSeq(seq);
        docNoRuleMapper.updateById(rule);

        StringBuilder no = new StringBuilder(rule.getPrefix());
        if (period != null && !period.isEmpty()) {
            no.append('-').append(period);
        }
        no.append('-').append(pad(seq, rule.getSeqLength()));
        return no.toString();
    }

    /** 按 date_pattern 计算当前期间标识；空 pattern → null（seq 永不跨期重置） */
    private String currentPeriod(String datePattern) {
        if (datePattern == null || datePattern.isBlank()) {
            return null;
        }
        return LocalDate.now().format(DateTimeFormatter.ofPattern(datePattern));
    }

    private String pad(long seq, Integer seqLength) {
        int length = seqLength == null || seqLength <= 0 ? 4 : seqLength;
        return String.format("%0" + length + "d", seq);
    }
}
