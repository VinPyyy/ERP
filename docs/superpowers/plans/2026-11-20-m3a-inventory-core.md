# M3a 搴撳瓨鍐呮牳锛堝潎浠?闂ㄩ潰/閿佸畾/瀵硅处锛夊疄鐜拌鍒?
> **闈㈠悜 AI 浠ｇ悊鐨勫伐浣滆€咃細** 蹇呴渶瀛愭妧鑳斤細浣跨敤 subagent-driven-development 鎴?executing-plans 閫愪换鍔″疄鐜般€傛楠ょ敤澶嶉€夋璺熻釜銆?
**鐩爣锛?* 钀藉湴 `StockService` 鍏ㄧ郴缁熷敮涓€鍔ㄥ簱瀛橀棬闈紙in/out/lock/unlock/reverse 濂戠害锛? 鍧囦环绾嚱鏁?+ 鍙屽眰閿佸畾 + 娴佹按蹇収 + 瀵硅处鍣紝浠?200 姝?property 娴嬭瘯鏀跺熬銆?
**鏋舵瀯锛?* `inventory.core` 绾嚱鏁帮紙鍧囦环銆侀噸鏀撅級锛沗inventory.api.StockService` 闂ㄩ潰鏀跺彛涓€鍒囧簱瀛樺彉鏇达紱骞跺彂鎸?(warehouse_id, sku_id) 鍗囧簭 FOR UPDATE锛圓DR-0005锛夛紱姣忕瑪鍐?insert-only `inv_flow` 鍚綑棰濆揩鐓э紱鍥涘眰涓嶅彉閲忥紙涓昏/鎵规琛?SN/閿佸畾锛夌敱 `StockInvariantAsserter` 缁熶竴鏂█銆?
**鎶€鏈爤锛?* Spring Boot 3 + MyBatis-Plus锛堣〃宸插湪 V1 寤哄ソ锛?*闆惰縼绉?*锛? JUnit/AssertJ銆?
**瑙勬牸锛?* `.scratch/erp-phase1/spec.md` 搂2.3/搂3a/搂3b/搂3e/搂3f + `issues/04` + ADR 0001/0002/0005銆傛€昏锛歔2026-11-20-m3-inventory-opening.md](2026-11-20-m3-inventory-opening.md)

## 鍏ㄥ眬绾︽潫

- 绮惧害锛歲ty(18,4)/price(18,6)/amount(18,2)锛屼竴寰?HALF_UP锛涢噾棰濇潈濞併€佸崟浠锋淳鐢熴€佸嚭娓呭綊闆躲€?- 澶氳鍙樺姩蹇呴』鎸?(warehouse_id, sku_id) 鍗囧簭鍔犻攣锛沗inv_flow` 姘镐笉鏀瑰垹銆?- 閿欒鐮佸彧鐢ㄥ凡鏈夛細30001/30003/30004/30005/30006銆?0001/10002銆?- 璐熷簱瀛樺紑鍏宠 `ConfigService.getBool("stock.allow.negative")`锛汼N/鎵规鍒嗘淳璇?`ProductReader`銆?- TDD 绾⑩啋缁库啋閲嶆瀯锛沗mvn test` 鍏ㄧ豢 + ArchUnit 7 瑙勫垯缁挎墠绠椾换鍔″畬鎴愩€?- 涓嶅紩 Redis锛沗reverse` 鍙疄鐜板绾︿笌鍏ュ簱绫诲啿姝ｏ紙鍑哄簱绫诲洖鍐茬暀缁?M4/M5 鐢ㄤ緥椹卞姩锛夈€?
## 瀹℃煡閲嶇偣锛圧eview Focus锛?
1. 鍑烘竻灏惧樊锛歚qty_out == qty` 缁撳叏棰濄€佹畫鍊煎綊闆?鈫?浠诲姟 1/3 `clearingSweepsRoundingResidue`銆?2. 棣栬骞跺彂 upsert锛氬弻绾跨▼鍚?(wh,sku) 棣栨鍏ュ簱鎾炲敮涓€閿?鈫?浠诲姟 3 `concurrentFirstInboundUpsertSafe`銆?3. 璐熷簱瀛樺紑鍏崇煩闃碉細false 鎷掞紙30001锛?true 鏀捐涓斿揩鐓у畧鎭?鈫?浠诲姟 3 `negativeSwitchMatrix`銆?4. SN 涓夋€侊細閲嶅鍦ㄥ簱 30005銆丱UT鈫扞N 缈诲洖鏇存柊浠撳簱/in_doc銆佸嚭搴撶姸鎬佷粨搴撲笉绗?30004 鈫?浠诲姟 3 `snMaterializeFlipAndReject`銆?5. 蹇収閾撅細`balance_* == 涓婁竴琛?+ 鏈甯︾鍙峰彉鍔╜锛屽啿姝ｈ鍏ラ摼 鈫?浠诲姟 5 property 鏀跺熬鏂█銆?
---

### 浠诲姟 1锛氬潎浠疯绠楀櫒锛堢函鍑芥暟锛?
**鏂囦欢锛?* 鍒涘缓 `inventory/core/StockState.java`銆乣inventory/core/AveragePriceCalculator.java`锛涙祴璇?`test/.../inventory/core/AveragePriceCalculatorTest.java`

- [ ] 姝ラ 1锛氬啓澶辫触娴嬭瘯鈥斺€旀柇瑷€鍊硷細`applyIn(0@0,10,10)鈫?10,100,10)`锛沗applyIn(10@100/10,5,13)鈫?15,165,11)`锛沗applyOut(15@165/11,8)鈫抍arry=88,(7,77,11)`锛沗clearingSweepsRoundingResidue`锛氬叆10@10.05鈫掑叆5@13鈫掑嚭15 鈫?carry=鍏ㄩ銆乶ext=(0,0,0)锛沗zeroPriceInboundKeepsAmount`锛涚函鍑芥暟涓嶅仛涓氬姟鏍￠獙锛堣秴鍑轰笉鎷︼紝璋冪敤鏂硅礋璐ｏ級
- [ ] 姝ラ 2锛歚mvn test -Dtest=AveragePriceCalculatorTest` 绾?- [ ] 姝ラ 3锛氬疄鐜扮鍚嶏細`record StockState(qty, costAmount, costPrice)` + `static zero()`锛沗record InResult(next, costAmountDelta)`锛沗record OutResult(next, carryAmount)`锛沗static applyIn/applyOut`
- [ ] 姝ラ 4锛氱豢
- [ ] 姝ラ 5锛欳ommit `feat(inventory): moving-average calculator with clearing sweep`

### 浠诲姟 2锛氬疄浣?+ Mapper + 闂ㄩ潰濂戠害

**鏂囦欢锛?* 鍒涘缓 `inventory/entity/` 8 涓疄浣擄紙InvLot/InvStock/InvLotStock/InvSn/InvStockLock/InvFlow/InvOpening/InvOpeningItem锛屽垪浠?V1 DDL 涓哄噯锛沗sn_list` 鐢?`JacksonTypeHandler` + `autoResultMap`锛夛紱`inventory/mapper/` 8 涓紙`InvStockMapper.selectForUpdate` 瑙佷笅锛夛紱`inventory/api/StockService.java` + `inventory/api/StockCmd.java`

```java
@Select("select * from inv_stock where warehouse_id=#{warehouseId} and sku_id=#{skuId} and deleted=0 for update")
InvStock selectForUpdate(@Param("warehouseId") Long warehouseId, @Param("skuId") Long skuId);

public interface StockService {
    void inbound(StockCmd.MoveCmd cmd);
    void outbound(StockCmd.MoveCmd cmd);
    void lock(StockCmd.LockCmd cmd);
    void unlock(StockCmd.LockRef ref);
    void reverse(StockCmd.ReverseCmd cmd);
    BigDecimal available(Long warehouseId, Long skuId);
}
// MoveCmd(Long warehouseId, BizRef bizRef, List<MoveLine> lines)
// MoveLine(Long skuId, BigDecimal qty, BigDecimal price, Long lotId, List<String> snList, Long bizDocItemId)
// BizRef(BizType bizType, DocType docType, Long docId, String docNo)
// LockCmd(Long warehouseId, LockRef ref, List<LockLine> lines)锛汱ockLine(Long skuId, BigDecimal qty)
// LockRef(DocType lockDocType, Long lockDocId, Long lockItemId)
// ReverseCmd(DocType docType, Long docId)
```

- [ ] 姝ラ 1锛歚StockServiceImpl` 绌哄３锛堟柟娉曟姏 `UnsupportedOperationException`锛?- [ ] 姝ラ 2锛歚mvn test`锛坈ontextLoads + ArchUnit 7 瑙勫垯锛夌豢鈥斺€旈棬闈笉 import purchase/sale锛屽疄浣撲笉澶栨硠
- [ ] 姝ラ 3锛欳ommit `feat(inventory): entities, mappers and StockService facade contract`

### 浠诲姟 3锛歩nbound/outbound 瀹炵幇

**鏂囦欢锛?* 淇敼 `inventory/service/StockServiceImpl.java`锛涙祴璇?`test/.../inventory/service/StockServiceMoveTest.java`锛園SpringBootTest + @Transactional + DataFactory锛?
- [ ] 姝ラ 1锛氬啓澶辫触娴嬭瘯锛堝悕绉?鏂█锛夛細`specSampleInIn10at10In5at13Out8`锛堜富琛?7@11/77銆佷笁鏉℃祦姘村揩鐓ч摼锛夛紱`outboundAtAverageKeepsPrice`锛沗clearingSweepsResidue`锛沗upsertCreatesRowOnFirstInbound`锛沗concurrentFirstInboundUpsertSafe`锛堝弻绾跨▼ latch锛屽悇鍏?5 鈫?qty=10锛夛紱`negativeSwitchMatrix`锛沗lotManagedRequiresLotAndKeepsInvariant`锛堢己 lotId鈫?0001銆佹壒娆′笉瓒斥啋30006銆丼UM 鎵规琛?涓昏锛夛紱`snMaterializeFlipAndReject`锛?0005/30004/缈诲洖锛夛紱`flowSnapshotFieldsComplete`
- [ ] 姝ラ 2锛歚mvn test -Dtest=StockServiceMoveTest` 绾?- [ ] 姝ラ 3锛氬疄鐜拌鐐癸細琛屽崌搴忔帓搴忊啋`selectForUpdate`锛堟棤琛?insert 鎹曡幏 `DuplicateKeyException` 閲嶈瘯锛夛紱鍏?`applyIn`銆佸嚭鍏堟牎楠岋紙寮€鍏?鎵规/SN锛夊啀 `applyOut`锛汼N 鐗╁寲/缈昏浆鎸?搂3b锛涙瘡琛屽啓 flow锛坆alance 鍙栧彉鍔ㄥ悗涓昏蹇収锛夛紱鍗曚簨鍔?- [ ] 姝ラ 4锛氱豢
- [ ] 姝ラ 5锛欳ommit `feat(inventory): StockService inbound/outbound with lots, SN and flows`

### 浠诲姟 4锛歭ock/unlock 鍙屽眰閿佸畾

**鏂囦欢锛?* 淇敼 `StockServiceImpl`锛涙祴璇?`test/.../inventory/service/StockServiceLockTest.java`

- [ ] 姝ラ 1锛氬け璐ユ祴璇曪細`lockAddsDetailAndSummary`锛沗unlockReleasesRemain`锛坮emain 缃?0 琛屼繚鐣欙級锛沗lockRequiresAvailableWhenNegativeOff`锛堝琛岄儴鍒嗕笉瓒斥啋30001 鍏ㄥ洖婊氾級锛沗lockedInvariantAfterMix`
- [ ] 姝ラ 2锛氱孩 鈫?姝ラ 3锛氬疄鐜帮紙FOR UPDATE鈫掓牎楠屸啋鎻?lock 琛屸啋`UPDATE inv_stock SET locked_qty=locked_qty+?`锛夆啋 姝ラ 4锛氱豢
- [ ] 姝ラ 5锛欳ommit `feat(inventory): order locking with two-layer invariant`

### 浠诲姟 5锛氬璐﹀櫒 + replay-check + property 娴嬭瘯

**鏂囦欢锛?* 鍒涘缓 `inventory/core/FlowReplayChecker.java`锛堢函鍑芥暟锛夈€乣inventory/service/StockReconcileService.java`銆乣inventory/dto/ReplayReportVO.java`銆乣inventory/controller/InventoryFlowController.java`锛坮eplay-check 绔偣锛宍@SaCheckPermission("inventory:flow:replay")`锛夛紱鎵╁睍 `test/.../support/StockInvariantAsserter.java`锛涙祴璇?`FlowReplayCheckerTest` + `StockServicePropertyTest`

- [ ] 姝ラ 1锛氬け璐ユ祴璇曪細`snapshotChainBrokenDetected`銆乣reversedEntryParticipatesInReplay`銆乣emptyFlowsNoDiff`锛沗StockServicePropertyTest`锛氬浐瀹氱瀛?200 姝ラ殢鏈?in/out/lock/unlock锛屾瘡 20 姝?`StockInvariantAsserter.assertConsistent()`锛堝洓灞?+ 閲嶆斁闆跺樊寮傦級锛汳ockMvc锛歳eplay-check 杩斿洖 `{consistent:true, diffs:[]}`銆佹棤鏉冮檺 403
- [ ] 姝ラ 2锛氱孩 鈫?姝ラ 3锛氬疄鐜帮紙Checker 鐢?AveragePriceCalculator 閲嶆斁姣斿 balance 蹇収涓庣粓鎬侊紱ReconcileService 鍏ㄩ噺鍥涘眰姣斿锛夆啋 姝ラ 4锛氱豢
- [ ] 姝ラ 5锛欳ommit `feat(inventory): flow replay reconciler, replay-check endpoint and property test`

---

## 鏈鍒掑畬鎴愬畾涔?
`mvn test` 鍏ㄧ豢锛堝惈 property 200 姝ワ級+ ArchUnit 缁匡紱`GET /api/inventory/flows/replay-check` 鎵嬪伐鍙敤锛涙棤鍓嶇鏀瑰姩銆?
