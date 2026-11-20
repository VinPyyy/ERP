package com.wille.erp.basedata.service;

import com.wille.erp.basedata.dto.CategorySaveRequest;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.dto.PartnerSaveRequest;
import com.wille.erp.basedata.dto.WarehouseSaveRequest;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2：类别（名称唯一/引用禁删）、仓库（编码唯一）、客户（编码唯一 + 分页）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CategoryWarehouseCustomerTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private WarehouseService warehouseService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ProductService productService;
    @Autowired
    private DataFactory dataFactory;

    @Test
    void categoryDuplicateNameThrows() {
        CategorySaveRequest request = new CategorySaveRequest();
        request.setName("食品");
        categoryService.create(request);

        assertThatThrownBy(() -> categoryService.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("类别");
    }

    @Test
    void categoryDeleteWithProductsThrows() {
        var category = dataFactory.category("零食");
        var request = new com.wille.erp.basedata.dto.ProductSaveRequest();
        request.setSkuCode("CAT01");
        request.setName("p");
        request.setUnit("个");
        request.setMgmtMode(MgmtMode.NONE);
        request.setCategoryId(category.getId());
        productService.create(request);

        assertThatThrownBy(() -> categoryService.delete(category.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品");
    }

    @Test
    void categoryDeleteWithChildrenThrows() {
        var parent = dataFactory.category("饮料");
        var child = dataFactory.category("碳酸");
        CategorySaveRequest move = new CategorySaveRequest();
        move.setName("碳酸");
        move.setParentId(parent.getId());
        categoryService.update(child.getId(), move);

        assertThatThrownBy(() -> categoryService.delete(parent.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("子类别");
    }

    @Test
    void warehouseDuplicateCodeThrows() {
        WarehouseSaveRequest request = new WarehouseSaveRequest();
        request.setCode("WH01");
        request.setName("主仓");
        warehouseService.create(request);

        assertThatThrownBy(() -> warehouseService.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编码");
    }

    @Test
    void warehouseListOrderedById() {
        WarehouseSaveRequest a = new WarehouseSaveRequest();
        a.setCode("WA");
        a.setName("仓A");
        WarehouseSaveRequest b = new WarehouseSaveRequest();
        b.setCode("WB");
        b.setName("仓B");
        warehouseService.create(a);
        warehouseService.create(b);

        assertThat(warehouseService.listAll()).extracting("code").contains("WA", "WB");
    }

    @Test
    void customerDuplicateCodeThrows() {
        PartnerSaveRequest request = new PartnerSaveRequest();
        request.setCode("C001");
        request.setName("客户甲");
        customerService.create(request);

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编码");
    }

    @Test
    void customerPageKeywordFilter() {
        PartnerSaveRequest a = new PartnerSaveRequest();
        a.setCode("C010");
        a.setName("北京客户");
        PartnerSaveRequest b = new PartnerSaveRequest();
        b.setCode("C011");
        b.setName("上海客户");
        customerService.create(a);
        customerService.create(b);

        PartnerQuery query = new PartnerQuery();
        query.setKeyword("北京");
        var page = customerService.page(query);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords().get(0).getCode()).isEqualTo("C010");
    }
}
