package com.wille.erp.sale.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseProduct;
import com.wille.erp.basedata.entity.BaseWarehouse;
import com.wille.erp.common.api.ErrorCode;
import com.wille.erp.common.enums.DocStatus;
import com.wille.erp.common.enums.MgmtMode;
import com.wille.erp.common.exception.BizException;
import com.wille.erp.sale.dto.QuoteItemRequest;
import com.wille.erp.sale.dto.QuoteSaveRequest;
import com.wille.erp.sale.entity.SalOrder;
import com.wille.erp.sale.entity.SalOrderItem;
import com.wille.erp.sale.entity.SalQuote;
import com.wille.erp.sale.mapper.SalOrderItemMapper;
import com.wille.erp.sale.mapper.SalOrderMapper;
import com.wille.erp.sale.mapper.SalQuoteMapper;
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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 销售报价单（M5 任务 2，spec §2.5/§8-1）：不锁库存；转单生成一张订单草稿后头 COMPLETED。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuoteServiceTest {

    @Autowired
    private QuoteService quoteService;
    @Autowired
    private SalQuoteMapper quoteMapper;
    @Autowired
    private SalOrderMapper orderMapper;
    @Autowired
    private SalOrderItemMapper orderItemMapper;
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

    private QuoteSaveRequest request(BaseCustomer cu, BaseProduct p1, BaseProduct p2) {
        QuoteSaveRequest req = new QuoteSaveRequest();
        req.setCustomerId(cu.getId());
        req.setValidUntil(LocalDate.now().plusDays(30));
        QuoteItemRequest l1 = new QuoteItemRequest();
        l1.setSkuId(p1.getId());
        l1.setQty(new BigDecimal("10"));
        l1.setPrice(new BigDecimal("8"));
        QuoteItemRequest l2 = new QuoteItemRequest();
        l2.setSkuId(p2.getId());
        l2.setQty(new BigDecimal("2"));
        l2.setPrice(new BigDecimal("15.5"));
        req.setItems(List.of(l1, l2));
        return req;
    }

    @Test
    void createAssignsXsbjDocNoAndAuditIsConditional() {
        BaseCustomer cu = dataFactory.customer("QT-C1");
        BaseProduct p1 = dataFactory.product("QT-P1", MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("QT-P2", MgmtMode.NONE);

        Long id = quoteService.create(request(cu, p1, p2));
        SalQuote doc = quoteMapper.selectById(id);
        assertThat(doc.getDocNo()).startsWith("XSBJ");
        assertThat(doc.getStatus()).isEqualTo(DocStatus.DRAFT);

        quoteService.audit(id);
        assertThat(quoteMapper.selectById(id).getStatus()).isEqualTo(DocStatus.AUDITED);
        assertThatThrownBy(() -> quoteService.audit(id))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    }

    @Test
    void convertToOrderCarriesLinesAndCompletesQuote() {
        BaseCustomer cu = dataFactory.customer("QT-C2");
        BaseWarehouse wh = dataFactory.warehouse("QT-W2");
        BaseProduct p1 = dataFactory.product("QT-P3", MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("QT-P4", MgmtMode.NONE);
        Long id = quoteService.create(request(cu, p1, p2));
        quoteService.audit(id);

        Long orderId = quoteService.convertToOrder(id, wh.getId());

        SalOrder order = orderMapper.selectById(orderId);
        assertThat(order.getStatus()).isEqualTo(DocStatus.DRAFT);
        assertThat(order.getCustomerId()).isEqualTo(cu.getId());
        assertThat(order.getWarehouseId()).isEqualTo(wh.getId());
        List<SalOrderItem> lines = orderItemMapper.selectList(Wrappers.<SalOrderItem>lambdaQuery()
                .eq(SalOrderItem::getDocId, orderId).orderByAsc(SalOrderItem::getLineNo));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getQty()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(lines.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("80"));
        assertThat(lines.get(0).getDoneQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lines.get(0).getLineStatus()).isEqualTo(10);
        assertThat(quoteMapper.selectById(id).getStatus()).isEqualTo(DocStatus.COMPLETED);
    }

    @Test
    void convertAfterCompletedRejected() {
        BaseCustomer cu = dataFactory.customer("QT-C3");
        BaseWarehouse wh = dataFactory.warehouse("QT-W3");
        BaseProduct p1 = dataFactory.product("QT-P5", MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("QT-P6", MgmtMode.NONE);
        Long id = quoteService.create(request(cu, p1, p2));
        quoteService.convertToOrder(id, wh.getId());

        assertThatThrownBy(() -> quoteService.convertToOrder(id, wh.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已转单");
    }

    @Test
    void voidRequiresReasonAndDraftVoidAllowed() {
        BaseCustomer cu = dataFactory.customer("QT-C4");
        BaseProduct p1 = dataFactory.product("QT-P7", MgmtMode.NONE);
        BaseProduct p2 = dataFactory.product("QT-P8", MgmtMode.NONE);
        Long id = quoteService.create(request(cu, p1, p2));

        QuoteSaveRequest noReason = request(cu, p1, p2);
        assertThatThrownBy(() -> quoteService.voidQuote(id, "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("原因");
        assertThat(noReason).isNotNull();

        quoteService.voidQuote(id, "客户放弃");
        assertThat(quoteMapper.selectById(id).getStatus()).isEqualTo(DocStatus.VOIDED);
    }
}
