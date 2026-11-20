package com.wille.erp.inventory.api;

import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.DocType;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.inventory.entity.InvCount;
import com.wille.erp.inventory.entity.InvCountItem;
import com.wille.erp.inventory.mapper.InvCountItemMapper;
import com.wille.erp.inventory.mapper.InvCountMapper;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CountLockGuardTest {

    @Autowired
    private CountLockGuard guard;
    @Autowired
    private InvCountMapper countMapper;
    @Autowired
    private InvCountItemMapper countItemMapper;
    @Autowired
    private DataFactory dataFactory;

    private Long startCounting(BaseWarehouse wh, BaseProduct p, DocStatus status) {
        InvCount count = new InvCount();
        count.setDocNo("PD-TEST-" + System.nanoTime());
        count.setStatus(status);
        count.setDocDate(LocalDate.now());
        count.setCurrency("CNY");
        count.setWarehouseId(wh.getId());
        count.setFinFlag(0);
        countMapper.insert(count);
        InvCountItem item = new InvCountItem();
        item.setDocId(count.getId());
        item.setLineNo(1);
        item.setSkuId(p.getId());
        item.setSkuName(p.getName());
        countItemMapper.insert(item);
        return count.getId();
    }

    @Test
    void countingDetectsActiveCountDoc() {
        BaseWarehouse wh = dataFactory.warehouse("CLG1");
        BaseProduct p = dataFactory.product("CLGP1", MgmtMode.NONE);
        startCounting(wh, p, DocStatus.COUNTING);

        assertThat(guard.counting(wh.getId(), List.of(p.getId()))).isTrue();
        BaseProduct other = dataFactory.product("CLGP2", MgmtMode.NONE);
        assertThat(guard.counting(wh.getId(), List.of(other.getId()))).isFalse();
    }

    @Test
    void voidedCountReleasesLock() {
        BaseWarehouse wh = dataFactory.warehouse("CLG2");
        BaseProduct p = dataFactory.product("CLGP3", MgmtMode.NONE);
        startCounting(wh, p, DocStatus.VOIDED);

        assertThat(guard.counting(wh.getId(), List.of(p.getId()))).isFalse();
        assertThat(DocType.COUNT.getPrefix()).isEqualTo("PD");
    }
}
