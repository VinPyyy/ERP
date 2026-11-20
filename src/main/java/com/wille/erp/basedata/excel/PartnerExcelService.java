package com.wille.erp.basedata.excel;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wille.erp.common.api.ImportResultVO;
import com.wille.erp.basedata.dto.PartnerQuery;
import com.wille.erp.basedata.entity.BaseCustomer;
import com.wille.erp.basedata.entity.BaseSupplier;
import com.wille.erp.basedata.mapper.BaseCustomerMapper;
import com.wille.erp.basedata.mapper.BaseSupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 客户/供应商 Excel 导入/导出：与客户供应商结构对称（GLOSSARY）。
 * 全量校验、有错不落库、精确行号——与 ProductExcelService 同套规则。
 */
@Service
@RequiredArgsConstructor
public class PartnerExcelService {

    private final BaseCustomerMapper customerMapper;
    private final BaseSupplierMapper supplierMapper;

    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importCustomers(InputStream in) {
        return importPartner(in, "客户",
                code -> customerMapper.selectCount(Wrappers.<BaseCustomer>lambdaQuery()
                        .eq(BaseCustomer::getCode, code)),
                model -> {
                    BaseCustomer customer = new BaseCustomer();
                    fillPartner(customer::setCode, customer::setName, customer::setContact,
                            customer::setPhone, customer::setAddress, customer::setTaxNo, customer::setStatus,
                            customer::setRemark, model);
                    customerMapper.insert(customer);
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importSuppliers(InputStream in) {
        return importPartner(in, "供应商",
                code -> supplierMapper.selectCount(Wrappers.<BaseSupplier>lambdaQuery()
                        .eq(BaseSupplier::getCode, code)),
                model -> {
                    BaseSupplier supplier = new BaseSupplier();
                    fillPartner(supplier::setCode, supplier::setName, supplier::setContact,
                            supplier::setPhone, supplier::setAddress, supplier::setTaxNo, supplier::setStatus,
                            supplier::setRemark, model);
                    supplierMapper.insert(supplier);
                });
    }

    public byte[] exportCustomers(PartnerQuery query) {
        List<BaseCustomer> customers = customerMapper.selectList(Wrappers.<BaseCustomer>lambdaQuery()
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(BaseCustomer::getCode, query.getKeyword())
                                .or().like(BaseCustomer::getName, query.getKeyword()))
                .eq(query.getStatus() != null, BaseCustomer::getStatus, query.getStatus())
                .orderByAsc(BaseCustomer::getCode));
        return writeRows(customers.stream().map(c -> toModel(c.getCode(), c.getName(), c.getContact(),
                c.getPhone(), c.getAddress(), c.getTaxNo(), c.getRemark())).toList());
    }

    public byte[] exportSuppliers(PartnerQuery query) {
        List<BaseSupplier> suppliers = supplierMapper.selectList(Wrappers.<BaseSupplier>lambdaQuery()
                .and(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        w -> w.like(BaseSupplier::getCode, query.getKeyword())
                                .or().like(BaseSupplier::getName, query.getKeyword()))
                .eq(query.getStatus() != null, BaseSupplier::getStatus, query.getStatus())
                .orderByAsc(BaseSupplier::getCode));
        return writeRows(suppliers.stream().map(s -> toModel(s.getCode(), s.getName(), s.getContact(),
                s.getPhone(), s.getAddress(), s.getTaxNo(), s.getRemark())).toList());
    }

    private ImportResultVO importPartner(InputStream in, String label,
                                         Function<String, Long> dbCodeCount,
                                         Consumer<PartnerImportModel> inserter) {
        List<PartnerImportModel> rows = EasyExcel.read(in)
                .head(PartnerImportModel.class).sheet().doReadSync();

        List<ImportResultVO.RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            PartnerImportModel row = rows.get(i);
            String error = validateRow(row, seen, label, dbCodeCount);
            if (row.getCode() != null && !row.getCode().isBlank()) {
                seen.add(row.getCode().trim());
            }
            if (error != null) {
                errors.add(new ImportResultVO.RowError(i + 1, error));
            }
        }
        if (!errors.isEmpty()) {
            return new ImportResultVO(rows.size(), 0, errors);
        }
        for (PartnerImportModel row : rows) {
            inserter.accept(row);
        }
        return new ImportResultVO(rows.size(), rows.size(), List.of());
    }

    private static String validateRow(PartnerImportModel row, Set<String> seen, String label,
                                      Function<String, Long> dbCodeCount) {
        if (row.getCode() == null || row.getCode().isBlank()) {
            return label + "编码必填";
        }
        if (row.getName() == null || row.getName().isBlank()) {
            return label + "名称必填";
        }
        if (seen.contains(row.getCode().trim())) {
            return "文件内编码重复: " + row.getCode();
        }
        if (dbCodeCount.apply(row.getCode().trim()) > 0) {
            return label + "编码已存在: " + row.getCode();
        }
        return null;
    }

    private static void fillPartner(Consumer<String> code, Consumer<String> name, Consumer<String> contact,
                                    Consumer<String> phone, Consumer<String> address, Consumer<String> taxNo,
                                    Consumer<Integer> status, Consumer<String> remark, PartnerImportModel model) {
        code.accept(model.getCode().trim());
        name.accept(model.getName().trim());
        contact.accept(model.getContact());
        phone.accept(model.getPhone());
        address.accept(model.getAddress());
        taxNo.accept(model.getTaxNo());
        status.accept(1);
        remark.accept(model.getRemark());
    }

    private static PartnerImportModel toModel(String code, String name, String contact,
                                              String phone, String address, String taxNo, String remark) {
        PartnerImportModel model = new PartnerImportModel();
        model.setCode(code);
        model.setName(name);
        model.setContact(contact);
        model.setPhone(phone);
        model.setAddress(address);
        model.setTaxNo(taxNo);
        model.setRemark(remark);
        return model;
    }

    private byte[] writeRows(List<PartnerImportModel> models) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out, PartnerImportModel.class).sheet("往来单位").doWrite(models);
        return out.toByteArray();
    }
}
