package com.wille.erp.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.ConfigValueType;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.system.api.ConfigService;
import com.wille.erp.system.entity.SysConfig;
import com.wille.erp.system.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数：进程内缓存（ConcurrentHashMap），update 即刷新——无 Redis（spec §2.1）。
 * 单实例部署前提下缓存与库强一致（唯一写入口是 update）。
 */
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final SysConfigMapper configMapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public boolean getBool(String key) {
        return Boolean.parseBoolean(rawValue(key));
    }

    @Override
    public BigDecimal getDecimal(String key) {
        return new BigDecimal(rawValue(key));
    }

    @Override
    public int getInt(String key) {
        return Integer.parseInt(rawValue(key));
    }

    @Override
    public String getString(String key) {
        return rawValue(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String key, String value) {
        SysConfig config = findByKey(key);
        validateByType(config.getValueType(), value);
        config.setParamValue(value);
        configMapper.updateById(config);
        refreshCache(key, value);
    }

    /**
     * 写事务内立即可见（"修改后即时生效"语义），同时登记回滚失效钩子：
     * 事务未提交则逐出缓存键，下次读从 DB 重载已提交真值——
     * 既不污染缓存（测试 rolledBackTransactionDoesNotPolluteCache 钉住），也避免 afterCommit 方案的事务内读不到问题。
     */
    private void refreshCache(String key, String value) {
        cache.put(key, value);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) {
                                cache.remove(key);
                            }
                        }
                    });
        }
    }

    /**
     * 按 value_type 校验入参：脏值一旦入库，后续 getDecimal/getInt 解析会直接 500，
     * 所以唯一写入口必须挡住（前端的类型限制只是体验层，这里是正确性层）。
     */
    private void validateByType(ConfigValueType type, String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "参数值不能为空");
        }
        if (type == null) {
            return;
        }
        switch (type) {
            case BOOL -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "布尔参数只能填 true/false");
                }
            }
            case DECIMAL -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "小数参数格式不正确: " + value);
                }
            }
            case INT -> {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "整数参数格式不正确: " + value);
                }
            }
            case STRING -> {
                // 无格式约束
            }
        }
    }

    private String rawValue(String key) {
        return cache.computeIfAbsent(key, k -> {
            String value = findByKey(k).getParamValue();
            return value == null ? "" : value;
        });
    }

    private SysConfig findByKey(String key) {
        SysConfig config = configMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getParamKey, key));
        if (config == null) {
            throw new BizException(ErrorCode.PARAM_NOT_FOUND, "系统参数不存在: " + key);
        }
        return config;
    }
}
