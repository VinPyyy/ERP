package com.wille.erp;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块依赖方向守护（spec §1，构建必跑）——AI 辅助开发的"安全带"。
 * M0 阶段业务包尚空（failOnEmptyShould=false），规则随 M1+ 代码增长持续生效。
 */
@AnalyzeClasses(packages = "com.wille.erp", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final String[] DOC_MODULES = {"..erp.purchase..", "..erp.sale..", "..erp.inventory.."};

    /** R1：basedata/common/system 不得依赖任何单据业务包 */
    @ArchTest
    static final ArchRule foundationMustNotDependOnDocumentModules =
            noClasses().that().resideInAnyPackage("..erp.common..", "..erp.system..", "..erp.basedata..")
                    .should().dependOnClassesThat().resideInAnyPackage(DOC_MODULES);

    /** R2：inventory 不知道单据语义，只接受 Cmd——不得依赖 purchase/sale */
    @ArchTest
    static final ArchRule inventoryMustNotDependOnPurchaseOrSale =
            noClasses().that().resideInAPackage("..erp.inventory..")
                    .should().dependOnClassesThat().resideInAnyPackage("..erp.purchase..", "..erp.sale..");

    /** R3a：purchase 不得依赖 sale */
    @ArchTest
    static final ArchRule purchaseMustNotDependOnSale =
            noClasses().that().resideInAPackage("..erp.purchase..")
                    .should().dependOnClassesThat().resideInAPackage("..erp.sale..");

    /** R3b：sale 不得依赖 purchase */
    @ArchTest
    static final ArchRule saleMustNotDependOnPurchase =
            noClasses().that().resideInAPackage("..erp.sale..")
                    .should().dependOnClassesThat().resideInAPackage("..erp.purchase..");

    /** R4：业务模块访问 basedata/system 只能走 .api 子包，不得触碰内部实现包 */
    @ArchTest
    static final ArchRule businessModulesOnlyReachFoundationApis =
            noClasses().that().resideInAnyPackage(DOC_MODULES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..erp.basedata.controller..", "..erp.basedata.service..",
                            "..erp.basedata.mapper..", "..erp.basedata.entity..", "..erp.basedata.dto..",
                            "..erp.system.controller..", "..erp.system.service..",
                            "..erp.system.mapper..", "..erp.system.entity..", "..erp.system.dto..");

    /** R4b：purchase/sale 动库存只能走 inventory.api 门面（StockService），禁触内部包 */
    @ArchTest
    static final ArchRule stockAccessOnlyViaApiFacade =
            noClasses().that().resideInAnyPackage("..erp.purchase..", "..erp.sale..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..erp.inventory.controller..", "..erp.inventory.service..",
                            "..erp.inventory.mapper..", "..erp.inventory.entity..",
                            "..erp.inventory.dto..", "..erp.inventory.core..");

    /** R5：任何模块不得 import 其他模块的 mapper/entity（跨模块直接查库）；common 为共享基建豁免 */
    @ArchTest
    static final ArchRule noCrossModulePersistenceAccess =
            noClasses().should(new ArchCondition<JavaClass>("depend on other modules' mapper/entity classes") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    String ownModule = moduleOf(item.getPackageName());
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        JavaClass target = dependency.getTargetClass();
                        String targetModule = moduleOf(target.getPackageName());
                        boolean persistenceClass = target.getPackageName()
                                .matches("com\\.wille\\.erp\\.[a-z]+\\.(mapper|entity)(\\..*)?");
                        if (persistenceClass && targetModule != null
                                && !"common".equals(targetModule) && !targetModule.equals(ownModule)) {
                            events.add(SimpleConditionEvent.satisfied(item,
                                    item.getName() + " accesses persistence class " + target.getName()
                                            + " of module '" + targetModule + "'"));
                        }
                    }
                }
            });

    /** com.wille.erp.<module>.… → module 名；非项目包 → null */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith("com.wille.erp.")) {
            return null;
        }
        String rest = packageName.substring("com.wille.erp.".length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
