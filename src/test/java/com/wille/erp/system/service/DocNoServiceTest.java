package com.wille.erp.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.system.api.DocNoService;
import com.wille.erp.system.entity.SysDocNoRule;
import com.wille.erp.system.mapper.SysDocNoRuleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 验收（issue 02）：编号格式、顺序递增、跨期重置、50 线程并发取号无重号。
 * 不用 @Transactional：取号语义就是"独立提交、行锁串行化"，必须真实落库；
 * @AfterEach 把 SAL_ORDER 规则行复位。
 */
@SpringBootTest
@ActiveProfiles("test")
class DocNoServiceTest {

    @Autowired
    private DocNoService docNoService;
    @Autowired
    private SysDocNoRuleMapper docNoRuleMapper;

    @AfterEach
    void resetRule() {
        SysDocNoRule rule = docNoRuleMapper.selectOne(Wrappers.<SysDocNoRule>lambdaQuery()
                .eq(SysDocNoRule::getDocType, DocType.SAL_ORDER));
        rule.setCurrentSeq(0L);
        rule.setCurrentPeriod(null);
        docNoRuleMapper.updateById(rule);
    }

    private SysDocNoRule salOrderRule() {
        return docNoRuleMapper.selectOne(Wrappers.<SysDocNoRule>lambdaQuery()
                .eq(SysDocNoRule::getDocType, DocType.SAL_ORDER));
    }

    private static long seqOf(String docNo) {
        return Long.parseLong(docNo.substring(docNo.lastIndexOf('-') + 1));
    }

    @Test
    void nextNoMatchesFormat() {
        String no = docNoService.nextNo(DocType.SAL_ORDER);

        // XSDD-yyyyMMdd-0001
        assertThat(no).matches("XSDD-\\d{8}-\\d{4}");
    }

    @Test
    void nextNoIncrementsSequentially() {
        String first = docNoService.nextNo(DocType.SAL_ORDER);
        String second = docNoService.nextNo(DocType.SAL_ORDER);

        assertThat(seqOf(second)).isEqualTo(seqOf(first) + 1);
    }

    @Test
    void periodChangeResetsSeq() {
        SysDocNoRule rule = salOrderRule();
        rule.setCurrentPeriod("20000101"); // 陈旧期间
        rule.setCurrentSeq(57L);
        docNoRuleMapper.updateById(rule);

        String no = docNoService.nextNo(DocType.SAL_ORDER);

        assertThat(seqOf(no)).isEqualTo(1L);
        assertThat(salOrderRule().getCurrentPeriod()).isNotEqualTo("20000101");
    }

    @Test
    void concurrent50ThreadsProduceNoDuplicates() throws Exception {
        int threads = 50;
        int perThread = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> issued = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    issued.add(docNoService.nextNo(DocType.SAL_ORDER));
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(issued).hasSize(threads * perThread);
    }
}
