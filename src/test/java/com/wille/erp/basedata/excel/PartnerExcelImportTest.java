package com.wille.erp.basedata.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.mapper.BaseCustomerMapper;
import com.wille.erp.basedata.mapper.BaseSupplierMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PartnerExcelImportTest {

    @Autowired
    private PartnerExcelService partnerExcelService;
    @Autowired
    private BaseCustomerMapper customerMapper;
    @Autowired
    private BaseSupplierMapper supplierMapper;

    private ByteArrayInputStream customerXlsx(List<PartnerImportModel> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, PartnerImportModel.class).sheet("客户").doWrite(rows);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static PartnerImportModel row(String code, String name) {
        PartnerImportModel model = new PartnerImportModel();
        model.setCode(code);
        model.setName(name);
        return model;
    }

    @Test
    void customersAllValidInserted() {
        ImportResultVO result = partnerExcelService.importCustomers(customerXlsx(List.of(
                row("CX01", "客户一"), row("CX02", "客户二"))));

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(customerMapper.selectCount(
                Wrappers.<BaseCustomer>lambdaQuery().likeRight(BaseCustomer::getCode, "CX0"))).isEqualTo(2L);
    }

    @Test
    void customersWithDuplicateCodeNothingInserted() {
        ImportResultVO result = partnerExcelService.importCustomers(customerXlsx(List.of(
                row("CX10", "ok"), row("CX10", "重复"))));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().get(0).getRow()).isEqualTo(2);
        assertThat(customerMapper.selectCount(
                Wrappers.<BaseCustomer>lambdaQuery().eq(BaseCustomer::getCode, "CX10"))).isZero();
    }

    @Test
    void customersMissingNameRowReported() {
        ImportResultVO result = partnerExcelService.importCustomers(customerXlsx(List.of(
                row("CX20", ""))));

        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getReason()).contains("名称"));
    }

    @Test
    void suppliersImportSeparateTable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, PartnerImportModel.class).sheet("供应商")
                .doWrite(List.of(row("SP01", "供应商一")));

        ImportResultVO result = partnerExcelService.importSuppliers(new ByteArrayInputStream(out.toByteArray()));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(supplierMapper.selectCount(
                Wrappers.<BaseSupplier>lambdaQuery().eq(BaseSupplier::getCode, "SP01"))).isEqualTo(1L);
    }
}
