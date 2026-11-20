package com.wille.erp.purchase.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.purchase.dto.InquiryItemRequest;
import com.wille.erp.purchase.dto.InquirySaveRequest;
import com.wille.erp.purchase.dto.InquiryVO;
import com.wille.erp.purchase.entity.PurInquiry;
import com.wille.erp.purchase.entity.PurInquiryItem;
import com.wille.erp.purchase.entity.PurOrder;
import com.wille.erp.purchase.entity.PurOrderItem;
import com.wille.erp.purchase.mapper.PurInquiryItemMapper;
import com.wille.erp.purchase.mapper.PurInquiryMapper;
import com.wille.erp.purchase.mapper.PurOrderItemMapper;
import com.wille.erp.purchase.mapper.PurOrderMapper;
import com.wille.erp.support.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 询价单（M4 任务 2，spec §2.4）：不碰库存财务；选中行按供应商分组转订单草稿。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InquiryServiceTest {

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private PurInquiryMapper inquiryMapper;
    @Autowired
    private PurInquiryItemMapper itemMapper;
    @Autowired
    private PurOrderMapper orderMapper;
    @Autowired
    private PurOrderItemMapper orderItemMapper;
    @Autowired
    private DataFactory dataFactory;

    @BeforeEach
    void bindAdmin() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest(), new MockHttpServletResponse()));
        StpUtil.login(1L);
    }

    @AfterEach
    void clearLogin() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private InquiryItemRequest line(Long sku, Long supplier, String qty, String price) {
        InquiryItemRequest item = new InquiryItemRequest();
        item.setSkuId(sku);
        item.setSupplierId(supplier);
        item.setQty(new BigDecimal(qty));
        item.setPrice(new BigDecimal(price));
        item.setLeadTimeDays(7);
        return item;
    }

    @Test
    void createAssignsCgxjDocNoAndAuditIsConditional() {
        BaseWarehouse wh = dataFactory.warehouse("INQ-W1");
        BaseProduct p = dataFactory.product("INQ-P1", MgmtMode.NONE);
        BaseSupplier s = dataFactory.supplier("INQ-S1");

        InquirySaveRequest request = new InquirySaveRequest();
        request.setTitle("十一月询价");
        request.setItems(List.of(line(p.getId(), s.getId(), "100", "12.5")));
        Long id = inquiryService.create(request);

        PurInquiry doc = inquiryMapper.selectById(id);
        assertThat(doc.getDocNo()).startsWith("CGXJ");
        assertThat(doc.getStatus().getCode()).isEqualTo(10);

        inquiryService.audit(id);
        assertThatThrownBy(() -> inquiryService.audit(id))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    }

    @Test
    void convertGroupsSelectedLinesBySupplier() {
        BaseWarehouse wh = dataFactory.warehouse("INQ-W2");
        BaseProduct p1 = dataFactory.product("INQ-P2", MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("INQ-P3", MgmtMode.NONE);
        BaseSupplier sa = dataFactory.supplier("INQ-SA");
        BaseSupplier sb = dataFactory.supplier("INQ-SB");
        BaseSupplier sc = dataFactory.supplier("INQ-SC");

        InquirySaveRequest request = new InquirySaveRequest();
        request.setItems(List.of(
                line(p1.getId(), sa.getId(), "10", "5"),
                line(p2.getId(), sa.getId(), "20", "6"),
                line(p1.getId(), sb.getId(), "10", "4.8"),
                line(p2.getId(), sc.getId(), "20", "7")));
        Long id = inquiryService.create(request);

        List<PurInquiryItem> items = itemMapper.selectList(Wrappers.<PurInquiryItem>lambdaQuery()
                .eq(PurInquiryItem::getDocId, id).orderByAsc(PurInquiryItem::getLineNo));
        // 选中 A 组两行 + B 组一行；C 不选
        inquiryService.selectLine(id, items.get(0).getId(), true);
        inquiryService.selectLine(id, items.get(1).getId(), true);
        inquiryService.selectLine(id, items.get(2).getId(), true);

        List<Long> orderIds = inquiryService.convertToOrders(id, wh.getId());

        assertThat(orderIds).hasSize(2);
        List<PurOrder> orders = orderMapper.selectList(Wrappers.<PurOrder>lambdaQuery()
                .in(PurOrder::getId, orderIds));
        assertThat(orders).extracting(PurOrder::getSupplierId)
                .containsExactlyInAnyOrder(sa.getId(), sb.getId());
        PurOrder orderA = orders.stream().filter(o -> o.getSupplierId().equals(sa.getId())).findFirst().orElseThrow();
        assertThat(orderA.getWarehouseId()).isEqualTo(wh.getId());
        assertThat(orderA.getStatus().getCode()).isEqualTo(10);
        List<PurOrderItem> linesA = orderItemMapper.selectList(Wrappers.<PurOrderItem>lambdaQuery()
                .eq(PurOrderItem::getDocId, orderA.getId()).orderByAsc(PurOrderItem::getLineNo));
        assertThat(linesA).hasSize(2);
        assertThat(linesA.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(linesA.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("50"));
        // 转单后询价单置 COMPLETED
        assertThat(inquiryMapper.selectById(id).getStatus().getCode()).isEqualTo(30);
    }

    @Test
    void voidRequiresReason() {
        BaseWarehouse wh = dataFactory.warehouse("INQ-W3");
        BaseProduct p = dataFactory.product("INQ-P4", MgmtMode.NONE);
        BaseSupplier s = dataFactory.supplier("INQ-S2");
        InquirySaveRequest request = new InquirySaveRequest();
        request.setItems(List.of(line(p.getId(), s.getId(), "1", "1")));
        Long id = inquiryService.create(request);

        assertThatThrownBy(() -> inquiryService.voidInquiry(id, "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
    }

    @Test
    void getAndPageFollowDocVoShapeWithEnumStatus() {
        // C2：询价契约统一为 {doc,items} + 枚举名状态（与订单/入库/退货一致，前端 DocVO 消费）
        BaseProduct p = dataFactory.product("INQ-P5", MgmtMode.NONE);
        BaseSupplier s = dataFactory.supplier("INQ-SD");
        InquirySaveRequest request = new InquirySaveRequest();
        request.setTitle("契约统一");
        request.setItems(List.of(line(p.getId(), s.getId(), "10", "5"), line(p.getId(), s.getId(), "5", "4")));
        Long id = inquiryService.create(request);
        inquiryService.audit(id);

        InquiryVO vo = inquiryService.get(id);
        assertThat(vo.getDoc().getId()).isEqualTo(id);
        assertThat(vo.getDoc().getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThat(vo.getItems()).hasSize(2);
        assertThat(vo.getItems().get(0).getSkuName()).isNotBlank();

        assertThat(inquiryService.page(1, 20, null).getRecords())
                .extracting(PurInquiry::getId).contains(id);
    }

    @Test
    void selectLineRejectsForeignDocItem() {
        // M1：select 路径 id 与行归属必须一致
        BaseProduct p = dataFactory.product("INQ-P6", MgmtMode.NONE);
        BaseSupplier s = dataFactory.supplier("INQ-SE");
        InquirySaveRequest request = new InquirySaveRequest();
        request.setItems(List.of(line(p.getId(), s.getId(), "1", "1")));
        Long docA = inquiryService.create(request);
        Long docB = inquiryService.create(request);
        PurInquiryItem lineB = itemMapper.selectList(Wrappers.<PurInquiryItem>lambdaQuery()
                .eq(PurInquiryItem::getDocId, docB)).get(0);

        assertThatThrownBy(() -> inquiryService.selectLine(docA, lineB.getId(), true))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
