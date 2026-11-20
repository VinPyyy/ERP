package com.wille.erp.inventory.api;

import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.inventory.entity.InvLot;
import com.wille.erp.inventory.mapper.InvLotMapper;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LotApiTest {

    @Autowired
    private LotApi lotApi;
    @Autowired
    private com.wille.erp.basedata.api.ProductReader productReader;
    @Autowired
    private InvLotMapper lotMapper;
    @Autowired
    private DataFactory dataFactory;

    @Test
    void materializeReusesBySkuLotNo() {
        BaseProduct p = dataFactory.product("LOTAPI1", MgmtMode.LOT);
        Long first = lotApi.materialize(p.getId(), "L-01", LocalDate.of(2026, 1, 1));
        Long second = lotApi.materialize(p.getId(), "L-01", LocalDate.of(2026, 1, 1));

        assertThat(second).isEqualTo(first);
        InvLot lot = lotMapper.selectById(first);
        assertThat(lot.getSkuId()).isEqualTo(p.getId());
        // expiry = 生产日期 + 商品保质期（从 ProductReader 派生期望，不写死 DataFactory 取值）
        Integer shelf = productReader.read(p.getId()).shelfLifeDays();
        LocalDate production = LocalDate.of(2026, 1, 1);
        assertThat(lot.getExpiryDate()).isEqualTo(shelf == null ? null : production.plusDays(shelf));
    }

    @Test
    void materializeWithoutProductionDateLeavesExpiryNull() {
        BaseProduct p = dataFactory.product("LOTAPI2", MgmtMode.LOT);
        Long id = lotApi.materialize(p.getId(), "L-02", null);
        assertThat(lotMapper.selectById(id).getExpiryDate()).isNull();
    }
}
