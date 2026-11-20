package com.wille.erp.basedata.service;

import com.wille.erp.basedata.dto.ProductSaveRequest;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 验收（issue 03）：编码唯一、管批次必填保质期、有引用改 mgmt_mode 被拒、删除引用校验。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductServiceTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private DataFactory dataFactory;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ProductSaveRequest req(String code, MgmtMode mode) {
        ProductSaveRequest request = new ProductSaveRequest();
        request.setSkuCode(code);
        request.setName("测试商品");
        request.setUnit("个");
        request.setMgmtMode(mode);
        request.setStatus(1);
        return request;
    }

    private void seedStockReference(Long skuId) {
        jdbcTemplate.update("INSERT INTO inv_stock (warehouse_id, sku_id, qty, cost_price, cost_amount, create_time) VALUES (1, ?, 10, 5.00, 50.00, NOW())", skuId);
    }

    @Test
    void createNoneModeSucceeds() {
        Long id = productService.create(req("P001", MgmtMode.NONE));

        BaseProduct product = productService.get(id);
        assertThat(product.getMgmtMode()).isEqualTo(MgmtMode.NONE);
    }

    @Test
    void createLotModeRequiresShelfLife() {
        assertThatThrownBy(() -> productService.create(req("P002", MgmtMode.LOT)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("保质期");
    }

    @Test
    void createLotModeWithShelfLifeOk() {
        ProductSaveRequest request = req("P003", MgmtMode.LOT_SN);
        request.setShelfLifeDays(180);

        Long id = productService.create(request);

        assertThat(productService.get(id).getShelfLifeDays()).isEqualTo(180);
    }

    @Test
    void createDuplicateSkuCodeThrows() {
        productService.create(req("P004", MgmtMode.NONE));

        assertThatThrownBy(() -> productService.create(req("P004", MgmtMode.NONE)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void updateKeepingOwnSkuCodeAllowed() {
        Long id = productService.create(req("P005", MgmtMode.NONE));
        ProductSaveRequest request = req("P005", MgmtMode.NONE);
        request.setName("改名了");

        productService.update(id, request);

        assertThat(productService.get(id).getName()).isEqualTo("改名了");
    }

    @Test
    void updateMgmtModeRejectedWhenStockReferenced() {
        Long id = productService.create(req("P006", MgmtMode.NONE));
        seedStockReference(id);
        ProductSaveRequest toLot = req("P006", MgmtMode.LOT);
        toLot.setShelfLifeDays(90);

        assertThatThrownBy(() -> productService.update(id, toLot))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("引用");
    }

    @Test
    void updateKeepingSameMgmtModeAllowedEvenWhenReferenced() {
        Long id = productService.create(req("P007", MgmtMode.NONE));
        seedStockReference(id);

        // 引用存在时不改模式的其他更新应放行
        assertThatCode(() -> productService.update(id, req("P007", MgmtMode.NONE)))
                .doesNotThrowAnyException();
    }

    @Test
    void deleteRejectedWhenReferenced() {
        Long id = productService.create(req("P008", MgmtMode.NONE));
        seedStockReference(id);

        assertThatThrownBy(() -> productService.delete(id))
                .isInstanceOf(BizException.class);
    }

    @Test
    void deleteWithoutReferenceIsLogical() {
        Long id = productService.create(req("P009", MgmtMode.NONE));

        productService.delete(id);

        assertThatThrownBy(() -> productService.get(id)).isInstanceOf(BizException.class);
    }
}
