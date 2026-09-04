package mes.app.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.sales.service.JigSetParser;
import mes.app.transaction.service.WbsPlanService;
import mes.app.definition.service.BomService;
import mes.app.definition.service.material.UnitPriceService;
import mes.app.sales.service.SujuService;
import mes.app.sales.service.SujuSyncService;
import mes.app.sales.service.SujuUploadService;
import mes.config.Settings;
import mes.domain.entity.*;
import mes.domain.entity.iljin.suju_detail;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.repository.iljin.SuJuDetailRepository;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import java.util.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.transaction.Transactional;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static mes.domain.services.CommonUtil.tryIntNull;

@Slf4j
@RestController
@RequestMapping("/api/sales/suju")
public class SujuController {

  @Autowired
  SujuRepository SujuRepository;

  @Autowired
  SujuService sujuService;

  @Autowired
  SujuUploadService sujuUploadService;

  @Autowired
  SujuHeadRepository sujuHeadRepository;

  @Autowired
  MaterialRepository materialRepository;

  @Autowired
  CompanyRepository companyRepository;

  @Autowired
  ProjectRepository projectRepository;

  @Autowired
  Settings settings;

  @Autowired
  SqlRunner sqlRunner;

  @Autowired
  DepartRepository departRepository;

  @Autowired
  UnitPriceService unitPriceService;

  @Autowired
  UnitRepository unitRepository;

  @Autowired
  private SujuRepository sujuRepository;

  @Autowired
  SuJuDetailRepository suJuDetailRepository;

  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private ShipmentRepository shipmentRepository;

  @Autowired
  BomService bomService;

  @Autowired
  UserCodeRepository userCodeRepository;

  @Autowired
  SujuSyncService sujuSyncService;

  @Autowired
  WbsPlanService wbsPlanService;

  private static final String DEFAULT_COMPANY_TYPE = "";

  /** 엑셀 업로드로 생성하는 공정 품목의 품목그룹 (mat_grp."Code") — MAKE 가공품 */
  private static final String PROC_MATERIAL_GROUP_CODE = "MAKE";

  // ===================================================================
  //  문서 보관 경로
  //   - 템플릿  : {DOC_TEMPLATE_PATH}/*.xlsx        (수동으로 비치)
  //   - 업로드분: {DOC_PATH}/upload/{수주번호}_{원본파일명}
  //
  //   BaljuOrderController 가 발주서 템플릿을 C:/Temp/mes21/문서/BaljuTemplate.xlsx
  //   로 두고 있어 같은 뿌리를 쓴다.
  //   윈도우에서도 '/' 를 쓴다. '\' 는 자바 문자열 이스케이프로 먹혀 경로가 깨진다.
  //
  //   ★ 템플릿만 하위폴더로 내렸다. {DOC_PATH} 를 그대로 나열하면
  //     BaljuTemplate.xlsx, DeliveryReceipt.xlsx 같은 다른 화면 양식까지
  //     수주 화면 목록에 딸려 나온다 (실제로 그랬다).
  //     DOC_PATH 자체는 업로드 원본 보관에도 쓰이므로 건드리지 말 것.
  // ===================================================================
  private static final String DOC_PATH          = "C:/Temp/mes21/문서";
  private static final String DOC_UPLOAD_PATH   = DOC_PATH + "/upload";
  private static final String DOC_TEMPLATE_PATH = DOC_PATH + "/수주";

  /** 양식으로 인정할 확장자. 폴더에 섞인 다른 파일은 목록에 넣지 않는다 */
  private static final Set<String> TEMPLATE_EXT = Set.of("xlsx", "xls");

  /** 자사명. 제작/설계 업체가 이 이름이면 제작구분을 내작으로 본다. */
  private static final String INHOUSE_COMPANY_NAME = "일진";

  /**
   * suju_head 의 수주명 컬럼명.
   *
   * <p>★ 확인 필요. 엔티티 setter 는 setSujuName 이지만 실제 컬럼이
   * {@code "SujuName"}(따옴표 필요) 인지 {@code suju_name}(따옴표 없음) 인지
   * 확정하지 못했다. 아래로 확인하고 맞춰 둘 것.
   * <pre>
   *   SELECT column_name FROM information_schema.columns
   *    WHERE table_name = 'suju_head' AND column_name ILIKE '%name%';
   * </pre>
   * 여기만 고치면 되고, 다른 곳은 JPA 엔티티가 매핑을 갖고 있어 영향 없다.
   */
  private static final String SUJU_NAME_COLUMN = "suju_name";

  // 수주 목록 조회
  @GetMapping("/read")
  public AjaxResult getSujuList(
    @RequestParam(value = "start") String start_date,
    @RequestParam(value = "end" ) String end_date,
    @RequestParam(value = "spjangcd") String spjangcd,
    @RequestParam(value = "company",required = false) String company,
    @RequestParam(value = "projno", required = false) String projno,
    HttpServletRequest request) {

    start_date = start_date + " 00:00:00";
    end_date = end_date + " 23:59:59";

    Timestamp start = Timestamp.valueOf(start_date);
    Timestamp end = Timestamp.valueOf(end_date);

    List<Map<String, Object>> items = this.sujuService.getSujuList(start, end,spjangcd, company, projno);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  // 수주 상세정보 조회
  @GetMapping("/detail")
  public AjaxResult getSujuDetail(
    @RequestParam("id") int id,
    HttpServletRequest request) {
    Map<String, Object> item = this.sujuService.getSujuDetail(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  //규격  테이블 리스트
  @GetMapping("/detail_list")
  public AjaxResult getDetailList(@RequestParam("sujuId") Integer sujuId) {
    AjaxResult result = new AjaxResult();
    if (sujuId == null || sujuId <= 0) {
      result.success = false;
      result.message = "잘못된 수주 ID입니다.";
      result.data = List.of();
      return result;
    }

    List<Map<String, Object>> details = sujuService.getDetailList(sujuId);
    result.success = true;
    result.data = details;
    return result;
  }

  // 제품 정보 조회
  @GetMapping("/product_info")
  public AjaxResult getSujuMatInfo(
    @RequestParam("product_id") int id,
    HttpServletRequest request) {
    Map<String, Object> item = this.sujuService.getSujuMatInfo(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  // 수주 등록
  @PostMapping("/manual_save")
  @Transactional
  public AjaxResult SujuSave(@RequestBody Map<String, Object> payload, Authentication auth) {
    User user = (User) auth.getPrincipal();

    AjaxResult result = new AjaxResult();
    //log.info("수주등록 들어온 데이터: payload:{}", payload);
    String jumunDateStr = (String) payload.get("JumunDate");
    String dueDateStr = (String) payload.get("DueDate");

    Date jumunDate = CommonUtil.trySqlDate(jumunDateStr);
    Date dueDate = CommonUtil.trySqlDate(dueDateStr);

    String companyName = (String) payload.get("CompanyName");
    if (companyName == null) companyName = (String) payload.get("OrderName");
    Integer companyId = Integer.parseInt(payload.get("order_id").toString());
    String sujuType = (String) payload.get("SujuType");
    String description = (String) payload.get("Description");
    String spjangcd = (String) payload.get("spjangcd");
    Integer order_id = Integer.parseInt(payload.get("order_id").toString()); //수주처
    String SuJuOrderName = (String) payload.get("OrderName");
    String DeliveryName = (String) payload.get("DeliveryName");  //납품처
    String amountStr = payload.get("totalAmountSum").toString().replace(",", "");

    double totalAmount = 0.0;
    try {
      if (amountStr != null && !amountStr.trim().isEmpty()) {
        totalAmount = Double.parseDouble(amountStr.trim().replace(",", ""));
      }
    } catch (NumberFormatException e) {
      // 무시하고 0 유지
    }
    List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

    SujuHead head;

    // ✅ suju_head 수정 여부 확인
    if (payload.containsKey("id") && payload.get("id") != null && !payload.get("id").toString().isEmpty()) {
      Integer headId = Integer.parseInt(payload.get("id").toString());
      head = sujuHeadRepository.findById(headId).orElse(new SujuHead());
    } else {
      head = new SujuHead();
      head.setJumunNumber(generateJumunNumber(jumunDate));
    }

    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(companyId);
    head.setSpjangcd(spjangcd);
    head.set_audit(user);
    head.setSujuType(sujuType);
    head.setTotalPrice(totalAmount);
    head.setDescription(description);
    head.setSuJuOrderId(order_id);
    head.setSuJuOrderName(SuJuOrderName);
    head.setDeliveryName(DeliveryName);
    head.setEstimateMemo((String) payload.get("EstimateMemo"));


    head.set_status("manual");
    head = sujuHeadRepository.save(head);
    // =========================================================
    // ✅ [삭제 동기화] 시작: payload에 없는 기존 상세행 삭제
    // =========================================================
    // 1) 이번 payload에 포함된 기존 suju_id 목록 수집
    List<Integer> incomingIds = new ArrayList<>();
    if (items != null) {
      for (Map<String, Object> item : items) {
        Integer sid = toIntegerOrNull(item.get("suju_id"));
        if (sid != null && sid > 0) incomingIds.add(sid);
      }
    }

    // 2) DB에 존재하는 기존 상세행 조회
    List<Suju> existingList = SujuRepository.findBySujuHeadId(head.getId());

    // 3) 기존행 중 payload에 없는 건 = 화면에서 삭제된 행 → DB에서도 삭제
    for (Suju ex : existingList) {
      Integer exId = ex.getId();
      if (exId == null) continue;

      if (!incomingIds.contains(exId)) {

        // 🔒 출하 연동이면 삭제 차단
        boolean hasShipment = shipmentRepository
                                .existsBySourceTableNameAndSourceDataPk("rela_data", exId);

        if (hasShipment) {
          result.success = false;
          result.message = "출하계획 또는 진행중인 수주는 삭제할 수 없습니다.";
          TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
          return result;
        }

        // ✅ FK 고려: 상세 먼저 삭제 후 본문 삭제
        suJuDetailRepository.deleteBySujuId(exId);
        SujuRepository.deleteById(exId);
      }
    }
    // ✅ [삭제 동기화] 끝

    for (Map<String, Object> item : items) {
      Suju suju;
      String standard = java.util.Objects.toString(
        item.containsKey("Standard") ? item.get("Standard") : item.get("standard"),
        ""
      );


      // ✅ 수정인지 확인
      if (item.containsKey("suju_id") && item.get("suju_id") != null && !item.get("suju_id").toString().isEmpty()) {
        Integer sujuId = Integer.parseInt(item.get("suju_id").toString());
        suju = SujuRepository.findById(sujuId).orElse(new Suju());

        // 클라값 미리 파싱 (저장 전에 비교용)
        Integer mid = toIntegerOrNull(item.get("Material_id"));
        Double qty = null;
        try {
          qty = Double.valueOf(String.valueOf(item.get("quantity")));
        } catch (Exception ignore) {
        }
        Integer unitPrice = null;
        try {
          unitPrice = Integer.valueOf(String.valueOf(item.get("unitPrice")));
        } catch (Exception ignore) {
        }
        Date newDueDate = dueDate; // 이미 위에서 만든 dueDate

        boolean isAdjustmentLine = (mid == null); // 단수정리 라인

        // 기존 DB 값과 핵심 변경 비교
        boolean coreChanged =
          !java.util.Objects.equals(suju.getMaterialId(), mid) ||
            !java.util.Objects.equals(suju.getSujuQty(), qty) ||
            !java.util.Objects.equals(suju.getSujuQty2(), qty) ||
            !java.util.Objects.equals(suju.getUnitPrice(), unitPrice) ||
            !java.util.Objects.equals(suju.getCompanyId(), companyId) ||
            !java.util.Objects.equals(suju.getDueDate(), newDueDate);

        // 정확한 출하 연동 여부 확인 (SourceTableName/SourceDataPk 기준)
        boolean hasShipment = shipmentRepository
                                .existsBySourceTableNameAndSourceDataPk("rela_data", sujuId);
        // ← Repository에 아래 시그니처 추가 필요:
        // boolean existsBySourceTableNameAndSourceDataPk(String sourceTableName, Integer sourceDataPk);

        // 출하 연동 + 핵심값 변경 + 단수정리 라인이 아니면 차단
        if (hasShipment && coreChanged && !isAdjustmentLine) {
          //throw new RuntimeException("출하계획 또는 진행중인 수주입니다.");
          result.success = false;
          result.message = "출하계획 또는 진행중인 수주입니다.";

          TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

          return result;
        }

        // (필요하면) 변경 없음이면 스킵
        // ✅ 규격/상세 변경도 "변경"으로 인식해야 함
        boolean standardChanged = !java.util.Objects.equals(
          java.util.Objects.toString(suju.getStandard(), ""),
          java.util.Objects.toString(standard, "")
        );

        // 표준상세 payload가 오면(빈 리스트 포함 여부는 정책에 따라 선택)
        Object sdObj = item.get("standardDetails");
        boolean hasDetailsPayload = (sdObj instanceof List) && !((List<?>) sdObj).isEmpty();

        boolean nothingChanged = !coreChanged
                                   && !standardChanged
                                   && !hasDetailsPayload
                                   && java.util.Objects.equals(suju.getTotalAmount(), tryIntNull(item.get("totalAmount")))
                                   && java.util.Objects.equals(suju.getDescription(), (String) item.get("description"));

        if (nothingChanged) continue;

      } else {
        suju = new Suju(); // 신규일 경우
        suju.setJumunNumber(head.getJumunNumber());
      }

      // 공통 필드 설정
      suju.setSujuHeadId(head.getId());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(companyId);
      suju.setCompanyName(companyName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.set_audit(user);

      String invatyn = item.get("VatIncluded").toString();

      //suju.setMaterialId(Integer.parseInt(item.get("Material_id").toString()));
      Integer mid = toIntegerOrNull(item.get("Material_id"));
      suju.setMaterialId(mid);
      suju.setMaterial_Name((String) item.get("txtProductName"));
      suju.setSujuQty(Double.parseDouble(item.get("quantity").toString()));
      suju.setSujuQty2(Double.parseDouble(item.get("quantity").toString()));
      suju.setUnitPrice(Integer.parseInt(item.get("unitPrice").toString()));
      suju.setPrice(Integer.parseInt(item.get("supplyAmount").toString()));
      suju.setVat(Integer.parseInt(item.get("VatAmount").toString()));
      suju.setTotalAmount(Integer.parseInt(item.get("totalAmount").toString()));
      suju.setProject_id((String) payload.get("projno"));
      suju.setInVatYN(invatyn);
      suju.setDescription((String) item.get("description"));
      suju.setStandard(standard);
      suju.setConfirm("0");

      // 단가 변경 시 처리
      Boolean unitPriceChanged = (Boolean) item.get("unitPriceChanged");
      if (unitPriceChanged != null && unitPriceChanged) {
        MultiValueMap<String, Object> priceData = new LinkedMultiValueMap<>();
        priceData.add("Material_id", suju.getMaterialId());
        priceData.add("Company_id", companyId);
        priceData.add("UnitPrice", suju.getUnitPrice());
        priceData.add("ApplyStartDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        priceData.add("type", "02");
        priceData.add("ChangerName", user.getUsername());
        priceData.add("user_id", user.getId());

        unitPriceService.saveCompanyUnitPrice(priceData);
      }

      SujuRepository.save(suju);
      Integer sujuId = suju.getId();

      // 우선: items 요소에 배열 형태로 온 경우
      Object sdObj = item.get("standardDetails");
      if (sdObj instanceof List) {
//        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) sdObj;
        saveSujuDetailsList(sujuId, details);
      } else {
        // 폴백: suffix 기반 JSON 문자열
        String detailJson = resolveDetailJsonForItem(item, payload);
        saveSujuDetailsFromJson(sujuId, detailJson);
      }
    }


    result.success = true;
    return result;
  }

  private static Integer toIntegerOrNull(Object v) {
    if (v == null) return null;
    if (v instanceof Number) return ((Number) v).intValue(); // 이미 숫자면 그대로

    String s = v.toString().trim().replace(",", ""); // 공백/콤마 정리
    if (s.isEmpty() || s.equals("-") || s.equals(".")) return null; // 빈값/중간입력 방어

    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null; // 필요하다면 throw로 바꿔 로깅
    }
  }

  private static String numStr(Object o) {
    if (o == null) return "";
    return o.toString().replace(",", "").trim();
  }

  private static String str(Object o) {
    return (o == null) ? "" : o.toString().trim();
  }

  /**
   * 파라미터가 중복 전송되면 Spring 이 "ZZ,ZZ" / ",ZZ" 형태로 바인딩한다.
   * 첫 번째 비어있지 않은 토큰을 반환. 없으면 null.
   */
  private static String firstToken(Object o) {
    String s = str(o);
    if (s.isEmpty()) return null;
    for (String t : s.split(",")) {
      String v = t.trim();
      if (!v.isEmpty()) return v;
    }
    return null;
  }

  /** 빈 문자열/공백은 NULL 로 정규화 (텍스트 컬럼 저장용) */
  private static String nullIfEmpty(Object o) {
    String s = str(o);
    return s.isEmpty() ? null : s;
  }

  private static Double dnum(Object o) {
    String v = numStr(o); // 콤마 제거 + trim
    if (v.isEmpty() || v.equals("-") || v.equals(".")) return 0d;
    try { return Double.valueOf(v); } catch (Exception e) { return 0d; }
  }

  private void saveSujuDetailsList(Integer sujuId, List<Map<String, Object>> details) {
    if (sujuId == null || sujuId <= 0 || details == null) return;

    // 수정 케이스 대비: 기존 상세 먼저 삭제
    suJuDetailRepository.deleteBySujuId(sujuId);

    for (Map<String, Object> d : details) {
      // 기본 필드
      String std = str(d.get("standard"));           // "7.25"
      Double qty = dnum(d.get("qty"));               // 2.0

      // 완전 공백 레코드는 스킵(규격/수량 둘 다 비면)
      if ((std == null || std.isEmpty()) && (qty == null || qty == 0d)) continue;

      // 금액/단위 필드 (프런트 키 매핑 포함)
      String unitName   = str(d.get("UnitName"));
      if (unitName.isEmpty()) unitName = str(d.get("unit"));      // 혹시 다른 키로 올 때

      Double unitPrice  = dnum(d.get("sd_UnitPrice"));
      if (unitPrice == 0d) unitPrice = dnum(d.get("UnitPrice"));  // 백업 키

      Double price      = dnum(d.get("sd_price"));
      if (price == 0d)  price = dnum(d.get("Price"));

      Double vat        = dnum(d.get("sd_vat"));
      if (vat == 0d)    vat   = dnum(d.get("Vat"));

      Double total      = dnum(d.get("TotalAmount"));

      // 누락값 보정(프런트 계산과 동일 규칙: 소수점 반올림)
      if (price == null || price == 0d) {
        price = Math.round((qty == null ? 0d : qty) * (unitPrice == null ? 0d : unitPrice)) * 1d;
      }
      if (vat == null || vat == 0d) {
        vat = Math.round(price * 0.1) * 1d; // 부가세 10%
      }
      if (total == null || total == 0d) {
        total = price + vat;
      }

      // 엔티티 저장
      suju_detail row = new suju_detail();
      row.setSujuId(sujuId);
      row.setStandard(std);
      row.setQty(qty == null ? 0d : qty);
      row.setUnitName(unitName);
      row.setUnitPrice(unitPrice);
      row.setPrice(price);
      row.setVat(vat);
      row.setTotalAmount(total);

      suJuDetailRepository.save(row);
    }
  }

  // ----------------------------------------------------------
// 유틸: payload에 존재하는 suffix 인덱스 추출
// ----------------------------------------------------------
  private static Set<Integer> findAllSuffixIndexes(Map<String, Object> payload, String keyPrefix) {
    Set<Integer> set = new LinkedHashSet<>();
    for (String k : payload.keySet()) {
      if (k.startsWith(keyPrefix + "_")) {
        String sfx = k.substring((keyPrefix + "_").length());
        try {
          set.add(Integer.parseInt(sfx));
        } catch (Exception ignore) {
        }
      }
    }
    return set;
  }

  // ----------------------------------------------------------
// 핵심: 현재 아이템에 대응하는 standard_detail_json을 찾아 반환
// ----------------------------------------------------------
  private String resolveDetailJsonForItem(Map<String, Object> item, Map<String, Object> payload) {
    // 1) 아이템 안에 직접 들어있으면 그걸 사용
    String direct = str(item.get("standard_detail_json"));
    if (!direct.isEmpty()) return direct;

    // 2) _rowIndex / rowIndex 로 찾기
    String[] idxKeys = new String[]{"_rowIndex", "rowIndex"};
    for (String key : idxKeys) {
      String idxStr = str(item.get(key));
      if (!idxStr.isEmpty()) {
        String k1 = "standard_detail_json_" + idxStr;
        String v1 = str(item.get(k1));
        if (!v1.isEmpty()) return v1;
        String v2 = str(payload.get(k1));
        if (!v2.isEmpty()) return v2;
      }
    }

    // 3) 값 매칭 기반으로 찾기
    String materialId = numStr(item.get("Material_id"));
    String productCode = str(item.get("product_code"));
    String productName = str(item.get("txtProductName"));
    String standard = str(item.get("standard"));
    if (standard.isEmpty()) standard = str(item.get("Standard"));

    Set<Integer> idxSet = new LinkedHashSet<>();
    idxSet.addAll(findAllSuffixIndexes(payload, "standard_detail_json"));
    if (idxSet.isEmpty()) {
      idxSet.addAll(findAllSuffixIndexes(payload, "Material_id"));
      idxSet.addAll(findAllSuffixIndexes(payload, "product_code"));
      idxSet.addAll(findAllSuffixIndexes(payload, "txtProductName"));
      idxSet.addAll(findAllSuffixIndexes(payload, "standard"));
    }

    for (Integer n : idxSet) {
      boolean match = false;

      if (!materialId.isEmpty() && materialId.equals(numStr(payload.get("Material_id_" + n)))) match = true;
      if (!match && !productCode.isEmpty() && productCode.equals(str(payload.get("product_code_" + n)))) match = true;
      if (!match && !productName.isEmpty() && productName.equals(str(payload.get("txtProductName_" + n)))) match = true;

      String pStd = str(payload.get("standard_" + n));
      if (pStd.isEmpty()) pStd = str(payload.get("Standard_" + n));
      if (!match && !standard.isEmpty() && standard.equals(pStd)) match = true;

      if (match) {
        String json = str(payload.get("standard_detail_json_" + n));
        if (!json.isEmpty()) return json;
      }
    }

    // 못 찾으면 null
    return null;
  }

  // JSON 문자열을 파싱해 suju_detail에 저장(수정 시 기존행 삭제)
  private void saveSujuDetailsFromJson(Integer sujuId, String detailJson) {
    if (sujuId == null || sujuId <= 0) return;
    if (detailJson == null || detailJson.trim().isEmpty()) return;

    try {
      List<Map<String, Object>> details =
        objectMapper.readValue(detailJson,
          new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

      saveSujuDetailsList(sujuId, details);

    } catch (Exception e) {
      // 필요 시 로깅
    }
  }

  public String generateJumunNumber(Date jumunDate) {
    String dateStr = new SimpleDateFormat("yyyyMMdd").format(jumunDate);

    String sql = """
        WITH upsert AS (
        	INSERT INTO seq_maker ("Code", "BaseDate", "CurrVal", "_modified")
        	SELECT 'JumunNumber', '20250626', 1, now()
        	WHERE NOT EXISTS (
        		SELECT 1 FROM seq_maker WHERE "Code" = 'JumunNumber' AND "BaseDate" = '20250626'
        	)
        	RETURNING "CurrVal"
        ),
        updated AS (
        	UPDATE seq_maker
        	SET "CurrVal" = "CurrVal" + 1, "_modified" = now()
        	WHERE "Code" = 'JumunNumber' AND "BaseDate" = '20250626'
        	RETURNING "CurrVal"
        )
        SELECT * FROM updated
        UNION ALL
        SELECT * FROM upsert;
        """;

    MapSqlParameterSource param = new MapSqlParameterSource();
    param.addValue("date", dateStr);

    Integer nextVal = this.sqlRunner.queryForObject(sql, param, (rs, rowNum) -> rs.getInt(1));
    return dateStr + "-" + String.format("%04d", nextVal);
  }

  // ===================================================================
  //  수주 헤더 삭제 본체
  //   /delete 와 엑셀 덮어쓰기(excel_save?overwrite_head_id=)가 이것을 공유한다.
  //   ★ 두 곳에 각각 만들지 말 것. 중복되면 삭제 규칙이 곧 갈라진다.
  // ===================================================================

  /** 헤더 삭제 결과. ok=false 면 호출부가 롤백하고 message 를 그대로 보여준다. */
  public static class HeadDeleteResult {
    public boolean ok = true;
    public String  message;
    public int bomRemoved;
    public int jobRemoved;
    public int matRemoved;

    static HeadDeleteResult fail(String msg) {
      HeadDeleteResult r = new HeadDeleteResult();
      r.ok = false;
      r.message = msg;
      return r;
    }

    public String summary() {
      return " (BOM " + bomRemoved + "건, 작업지시 " + jobRemoved + "건,"
               + " 품목 " + matRemoved + "건 정리)";
    }
  }

  /**
   * 수주 헤더 삭제.
   *
   * <p><b>수주만 지우면 안 된다.</b> 이 시스템에는 FK 가 하나도 없어서
   * (balju / bom / bom_comp / material / suju_line_bom 전부 확인됨) DB 가
   * 정합성을 막아주지 않는다. 예전 구현은 suju / suju_head 만 지웠고,
   * 그 결과 suju_head 가 사라진 suju_line_bom 8건 + bom 8건 + material 61건이
   * 실제로 남았다. 반드시 아래 순서를 지킬 것.
   *
   * <pre>
   *  1) 사전검사       발주 입고 / 작업지시 실적이 있으면 삭제 거부 (사유를 모아 보여준다)
   *  2) 품목 id 확보   ★ suju 를 지우기 전에. 지운 뒤에는 못 읽는다
   *  3) 발주 취소      입고 0 인 발주만 canceled. 물리삭제하지 않는다
   *  4) 라인 BOM 정리  bom_comp → bom → LINE 모품목 → 매핑
   *  5) 작업지시 정리  ★ suju 를 조인하므로 6) 보다 먼저
   *  6) suju/suju_head 벌크 DELETE 후 flush
   *  7) 품목 정리      ★ 6) 이후에. suju 가 남아 있으면 참조로 잡혀 안 지워진다
   * </pre>
   *
   * <p>정책(현장 확정): 이미 실체가 생긴 것은 되돌리지 않는다.
   * 입고가 진행된 발주, 실적이 찍힌 작업지시가 걸린 수주는 <b>삭제 차단</b>.
   * {@code manual_save_project} 의 행 삭제와 같은 규칙이라 판정 코드를 공유한다.
   *
   * <p>호출부는 반드시 트랜잭션 안이어야 한다. 3) 에서 방어선에 걸릴 때
   * 이미 취소한 발주를 되돌려야 하기 때문이다.
   *
   * <p>suju_detail 은 이전 프로젝트 잔재라 다루지 않는다.
   */
  private HeadDeleteResult deleteSujuHeadCore(Integer headId, User user) {
    HeadDeleteResult r = new HeadDeleteResult();

    // ── 1) 사전검사 ──
    //  발주 입고 / 작업지시 실적이 하나라도 있으면 아무것도 지우지 않는다.
    //  행마다 하나씩 막히면 사용자가 문제를 여러 번 발견하게 되므로 사유를 모아 보여준다.
    List<String> blockers = sujuSyncService.checkHeadDeletable(headId);
    if (!blockers.isEmpty()) {
      return HeadDeleteResult.fail("이미 진행된 작업이 있어 삭제할 수 없습니다.\n\n"
                                     + String.join("\n", blockers)
                                     + "\n\n해당 화면에서 먼저 처리하세요.");
    }

    // ── 2) 삭제 전에 품목 id 확보 ──
    //  ★ 순서 주의. suju 를 지운 뒤에는 어떤 품목이 걸려 있었는지 알 수 없다.
    List<Integer> rowMaterialIds = sujuSyncService.collectRowMaterialIds(headId);

    // ── 3) 발주 취소 ──
    //  입고 0 인 발주는 canceled 로 내리고 헤더 금액을 다시 계산한다.
    //  물리삭제하지 않는 것은 SujuSyncService 의 규약 (업체로 이미 나간 문서).
    for (Suju ex : SujuRepository.findBySujuHeadId(headId)) {
      String blocked = sujuSyncService.blockOrCancelBaljuBeforeDelete(
        ex.getId(), ex.getMaterial_Name(), user);
      if (blocked != null) {
        // 1) 에서 걸렀어야 하는 경우. 방어선으로 남긴다.
        return HeadDeleteResult.fail(blocked);
      }
    }

    // ── 4) 라인 BOM 정리 ──
    r.bomRemoved = sujuSyncService.removeAllLineBoms(headId).bomRemoved;

    // ── 5) 작업지시 정리 ──
    //  ★ suju 를 조인해 찾으므로 6) 보다 먼저여야 한다.
    //    실적이 있는 지시는 1) 에서 이미 막혔다.
    r.jobRemoved = sujuSyncService.removeWorkOrders(headId);

    // ── 6) suju / suju_head ──
    //  ★ flush 필수.
    //    deleteBySujuHeadId 는 @Modifying 벌크 JPQL 이라 영속성 컨텍스트를 우회하고,
    //    7) 의 SujuSyncService 는 SqlRunner(순수 JDBC) 로 다시 읽는다.
    //    sujuHeadRepository.deleteById 는 반대로 커밋 시점까지 미뤄지므로,
    //    flush 하지 않으면 7) 이 이미 지운 줄 알았던 데이터를 다시 본다.
    SujuRepository.deleteBySujuHeadId(headId);
    SujuRepository.flush();
    sujuHeadRepository.deleteById(headId);
    sujuHeadRepository.flush();

    // ── 7) 미참조 품목 정리 ──
    //  반드시 6) 이후. 판정은 suju / balju / bom / bom_comp / mat_inout / job_res 참조.
    r.matRemoved = sujuSyncService.deleteUnreferencedMaterials(rowMaterialIds);

    log.info("[delete] 수주 삭제 완료: head={} BOM={} 작업지시={} 품목={}",
      headId, r.bomRemoved, r.jobRemoved, r.matRemoved);
    return r;
  }

  // 수주 삭제
  @Transactional
  @PostMapping("/delete")
  public AjaxResult deleteSuju(
    @RequestParam("id") Integer id,
    @RequestParam("State") String State,
    @RequestParam("ShipmentStateName") String ShipmentStateName,
    Authentication auth) {

    AjaxResult result = new AjaxResult();
    User user = (User) auth.getPrincipal();

    // ── 0) 기존 검증 유지 ──
    if (State.equals("received") == false) {
      //received 아닌것만
      result.success = false;
      result.message = "수주상태만 삭제할 수 있습니다";
      return result;
    }
    if (ShipmentStateName != null && !ShipmentStateName.isEmpty()) {
      result.success = false;
      result.message = "출하된 수주는 삭제할 수 없습니다";
      return result;
    }

    HeadDeleteResult del = deleteSujuHeadCore(id, user);
    if (!del.ok) {
      result.success = false;
      result.message = del.message;
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    result.success = true;
    result.message = "삭제되었습니다." + del.summary();
    return result;
  }

  // ===================================================================
  //  엑셀 업로드 전 중복 확인
  //   화면이 업로드 직전에 물어본다. 같은 프로젝트의 수주를 전부 돌려주고,
  //   수주명까지 같은 것은 name_matched=true 로 표시한다.
  //
  //   ★ projno 만으로 중복 판정하지 말 것.
  //     한 프로젝트에 모델별로 여러 수주가 정상적으로 들어간다
  //     (실제 데이터: 프로젝트 2026-004 에 NQ6 / NQ9 각 79행).
  // ===================================================================
  @GetMapping("/project_suju_list")
  public AjaxResult getProjectSujuList(
    @RequestParam("projno") String projno,
    @RequestParam(value = "suju_name", required = false) String sujuName) {

    AjaxResult result = new AjaxResult();
    List<Map<String, Object>> out = new ArrayList<>();

    if (projno == null || projno.trim().isEmpty()) {
      result.success = true;
      result.data = out;
      return result;
    }

    String sql = """
      SELECT h.id,
             h."JumunNumber" AS jumun_number,
             h."JumunDate"   AS jumun_date,
             h.%s            AS suju_name,
             h._created      AS created,
             (SELECT count(*) FROM suju s WHERE s."SujuHead_id" = h.id) AS line_cnt,
             (SELECT count(*) FROM balju b
               WHERE b."PlanTableName" = 'suju'
                 AND COALESCE(b."State", 'draft') <> 'canceled'
                 AND b."PlanDataPk" IN (SELECT s.id FROM suju s WHERE s."SujuHead_id" = h.id)
             ) AS balju_cnt
        FROM suju_head h
       WHERE h.project_id = CAST(:projno AS varchar)
       ORDER BY h._created
      """.formatted(SUJU_NAME_COLUMN);

    String want = sujuName == null ? "" : sujuName.trim();

    for (Map<String, Object> m : sqlRunner.getRows(sql,
      new MapSqlParameterSource().addValue("projno", projno.trim()))) {

      Map<String, Object> row = new LinkedHashMap<>(m);
      Integer headId = toIntegerOrNull(m.get("id"));

      String nm = str(m.get("suju_name"));
      row.put("name_matched", !want.isEmpty() && want.equalsIgnoreCase(nm));

      // 덮어쓰기 가능 여부 = 삭제 가능 여부. /delete 와 같은 판정을 쓴다.
      List<String> blockers = sujuSyncService.checkHeadDeletable(headId);
      row.put("deletable", blockers.isEmpty());
      row.put("block_reason", blockers.isEmpty() ? null : String.join("\n", blockers));

      out.add(row);
    }

    result.success = true;
    result.data = out;
    return result;
  }

  // 단가 정보 가져오기
  @GetMapping("/readPriceSuju")
  public AjaxResult getPriceHistory(@RequestParam("mat_pk") int matPk,
                                    @RequestParam("company_id") int company_id,
                                    @RequestParam("JumunDate") String ApplyStartDate) {

    List<Map<String, Object>> items = this.sujuService.getPriceByMatAndComp(matPk, company_id, ApplyStartDate);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  /**
   * 엑셀 컬럼 순서
   * 업체명 - 사업부 - 프로젝트 - 발주번호 - 자재번호 - 품명 - 규격 - 수량 - 단가 - 금액 - 단위 - 발주일 - 요청일
   * #company_name_col = 0		# 업체명
   * #depart_name_col = 1		# 사업부 이름 - 부서로 등록함
   * #project_name_col = 2		# 프로젝트 이름
   * #jumun_number_col = 3		# 발주 받은 번호(수주 번호)
   * #prod_code_col = 4			# 자재 번호(품목 코드)
   * #prod_name_col = 5    		# 품명
   * #prod_standard1_col = 6	# 규격
   * #qty_col = 7				# 수량
   * #prod_unit_price_col = 8	# 단가
   * #total_price_col = 9		# 금액
   * #unit_name_col = 10		# 단위
   * #jumnun_date_col = 11		# 발주일
   * #due_date_col = 12			# 요청일
   **/
  // 수주 엑셀 업로드
  @Transactional
  @PostMapping("/upload_save")
  public AjaxResult saveSujuBulkData(
    @RequestParam(value = "data_date") String data_date,
    @RequestParam(value = "spjangcd") String spjangcd,
    @RequestParam(value = "upload_file") MultipartFile upload_file,
    MultipartHttpServletRequest multipartRequest,
    Authentication auth) throws FileNotFoundException, IOException {

    User user = (User) auth.getPrincipal();

//	 	int company_name_col = 0;
    int depart_name_col = 2;
    int project_name_col = 4;
    int jumun_number_col = 5;
    int prod_code_col = 7;
    int prod_name_col = 8;
    int prod_standard1_col = 9;
    int qty_col = 10;
    int prod_unit_price_col = 12;
    int total_price_col = 13;
    int unit_name_col = 14;
    int jumnun_date_col = 15;
    int due_date_col = 16;

    DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    LocalDateTime now = LocalDateTime.now();
    String formattedDate = dtf.format(now);
    String upload_filename = settings.getProperty("file_temp_upload_path") + formattedDate + "_" + upload_file.getOriginalFilename();


    if (new File(upload_filename).exists()) {
      new File(upload_filename).delete();
    }

    try (FileOutputStream destination = new FileOutputStream(upload_filename)) {
      destination.write(upload_file.getBytes());
    }

    List<List<String>> suju_file = this.sujuUploadService.excel_read(upload_filename);
    List<Company> CompanyList = companyRepository.findBySpjangcd(spjangcd);
    List<TB_DA003> projectList = projectRepository.findByIdSpjangcd(spjangcd);
    List<Material> materialList = materialRepository.findBySpjangcd(spjangcd);
    List<Depart> departList = departRepository.findBySpjangcd(spjangcd);
    List<Unit> unitList = unitRepository.findAll();
    Map<String, SujuHead> sujuHeadMap = new HashMap<>();

    List<Suju> sujuList = new ArrayList<>();

    Map<String, Company> companyMap = CompanyList.stream()
                                        .collect(Collectors.toMap(Company::getName, Function.identity()));

    Map<String, Depart> departMap = departList.stream()
                                      .collect(Collectors.toMap(Depart::getName, Function.identity()));

    Map<String, TB_DA003> projectMap = projectList.stream()
                                         .collect(Collectors.toMap(TB_DA003::getProjnm, Function.identity()));

    Map<String, Material> materialMap = materialList.stream()
                                          .filter(m -> m.getCustomerBarcode() != null && !m.getCustomerBarcode().trim().isEmpty())
                                          .collect(Collectors.toMap(
                                            Material::getCustomerBarcode,
                                            Function.identity(),
                                            (existing, duplicate) -> existing
                                          ));

    Map<String, Unit> unitMap = unitList.stream()
                                  .collect(Collectors.toMap(Unit::getName, Function.identity()));


    AjaxResult result = new AjaxResult();

    for (int i = 0; i < suju_file.size(); i++) {

      List<String> row = suju_file.get(i);

//			String company_name = row.get(company_name_col).trim();
      String company_name = "대양전기공업㈜";
      String depart_name = row.get(depart_name_col).trim();
      String rawProjectName = row.get(project_name_col).trim();
      String project_name = rawProjectName.split("\\s+")[0];
      String jumun_number = row.get(jumun_number_col).trim();
      String prod_code_raw = row.get(prod_code_col);
      String prod_code;

      try {
        // Excel에서 숫자로 인식된 경우 (Double 타입)
        double doubleValue = Double.parseDouble(prod_code_raw);
        prod_code = new BigDecimal(doubleValue).toPlainString();  // 소수점 없는 문자열로 변환
      } catch (NumberFormatException e) {
        // 애초에 문자열로 잘 들어온 경우
        prod_code = prod_code_raw.trim();
      }
      String prod_name = row.get(prod_name_col).trim();
      String prod_standard = row.get(prod_standard1_col).trim();
      Float floatQty = Float.parseFloat(row.get(qty_col).trim());
      Integer quantity = floatQty.intValue();
      Float unit_price = tryFloat(row.get(prod_unit_price_col));
      Float total_price = tryFloat(row.get(total_price_col));
      String raw = row.get(total_price_col);
      String unit_name = row.get(unit_name_col).trim();

      LocalDate jumun_date = parseFlexibleDate(row.get(jumnun_date_col).trim());
      LocalDate due_date = parseFlexibleDate(row.get(due_date_col).trim());

      Company company = companyMap.get(company_name);

      if (company == null) {
        result.message = "엑셀에 기입된 업체명 '" + company_name + "'이(가) 존재하지 않습니다.";
        result.success = false;
        return result;
      }

      // 부서 확인 또는 생성
      Depart depart = departMap.get(depart_name);

      if (depart == null) {
        depart = new Depart();
        depart.setName(depart_name);
        depart.setSpjangcd(spjangcd);
        depart.set_audit(user);
        depart = departRepository.save(depart);
        departMap.put(depart_name, depart);
      }

      // 단위 확인 또는 생성
      Unit unit = unitMap.get(unit_name);

      if (unit == null) {
        unit = new Unit();
        unit.setName(depart_name);
        unit.setSpjangcd(spjangcd);
        unit = unitRepository.save(unit);
        unitMap.put(unit_name, unit);
      }

      // Project 확인 또는 생성
      TB_DA003 project = projectMap.get(project_name);

      if (project == null) {
        project = new TB_DA003();
        String newProjNo = generateNewProjectNo();
        project.setId(new TB_DA003Id(spjangcd, newProjNo));
        project.setProjnm(project_name);
        project.setBalcltcd(company.getId());
        project.setBalcltnm(company_name);
        project = projectRepository.save(project);
        projectMap.put(project_name, project);
      }

      // Material 매칭
      Material material = materialMap.get(prod_code);
      LocalDateTime jumunDateTime = LocalDateTime.of(jumun_date, LocalTime.now());
      DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      String jumunDateTimeStr = jumunDateTime.format(formatter1);

      if (material == null) {
        material = new Material();
        material.setName(prod_name);
        material.setCode(prod_code);
        material.setFactory_id(1);
        material.setCustomerBarcode(prod_code);
        material.setStandard1(prod_standard);
        material.setMtyn("1");
        material.setUseyn("0");
        material.setMaterialGroupId(45);
        material.setSpjangcd(spjangcd);
        material.set_audit(user);
        material = materialRepository.save(material);
      } else {
        List<Map<String, Object>> items = this.sujuService.getPriceByMatAndComp(material.getId(), company.getId(), jumunDateTimeStr);

        Float oldUnitPrice = items.isEmpty() ? null : ((Number) items.get(0).get("UnitPrice")).floatValue();
        boolean unitPriceChanged = (oldUnitPrice == null || !Objects.equals(oldUnitPrice.intValue(), unit_price.intValue()));

        if (unitPriceChanged) {
          String hhmmss = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
          String applyStartDate = jumun_date + "T" + hhmmss;

          MultiValueMap<String, Object> priceData = new LinkedMultiValueMap<>();
          priceData.set("Material_id", material.getId());
          priceData.set("Company_id", company.getId());
          priceData.set("UnitPrice", unit_price.intValue()); // DB 정수형이면
          priceData.set("ApplyStartDate", applyStartDate);
          priceData.set("type", "02");
          priceData.set("user_id", user.getId());

          unitPriceService.saveCompanyUnitPrice(priceData);
        }

      }

      SujuHead sujuHead = sujuHeadMap.get(jumun_number);

      if (sujuHead == null) {
        sujuHead = sujuHeadRepository.findByJumunNumberAndSpjangcd(jumun_number, spjangcd)
                     .orElseGet(() -> {
                       SujuHead newHead = new SujuHead();
                       newHead.setCompany_id(company.getId());   // 해당 행 기준
                       newHead.setJumunDate(Date.valueOf(jumun_date));
                       newHead.setDeliveryDate(Date.valueOf(due_date));
                       newHead.setSpjangcd(spjangcd);
                       newHead.setJumunNumber(jumun_number);
                       newHead.set_audit(user);
                       newHead.set_audit(user);
                       newHead.setSujuType("sales");
                       return sujuHeadRepository.save(newHead);
                     });

        sujuHeadMap.put(jumun_number, sujuHead);  // 캐싱
      }

      Suju suju = new Suju();
      suju.setState("received");

      suju.setSujuQty(Double.valueOf(quantity));
      suju.setSujuQty2(Double.valueOf(quantity));
      suju.setCompanyId(company.getId());
      suju.setCompanyName(company_name);
      suju.setDueDate(Date.valueOf(due_date));
      suju.setJumunDate(Date.valueOf(jumun_date));
      suju.setJumunNumber(jumun_number);
      suju.setMaterialId(material.getId());
      suju.setAvailableStock((float) 0); // 없으면 0으로 보내기 추가
      suju.set_status("manual");
      suju.set_audit(user);
      suju.setUnitPrice(unit_price.intValue());
      suju.setPrice(total_price.intValue());
      suju.setVat((int) (total_price.intValue() * 0.1));
      suju.setTotalAmount(total_price.intValue() + (int) (total_price.intValue() * 0.1));
      suju.setInVatYN("N");
      suju.setProject_id(project.getId().getProjno());
      suju.setSpjangcd(spjangcd);
      suju.setConfirm("0");
      suju.setSujuHeadId(sujuHead.getId());
      sujuList.add(suju);

      try {
      } catch (Exception e) {
        log.error("Insert 실패 - row {}: {}", i, e.getMessage());
        continue;
      }

    }

    SujuRepository.saveAll(sujuList);


    result.success = true;
    return result;
  }

  private String generateNewProjectNo() {
    String year = String.valueOf(LocalDate.now().getYear());

    String maxProjNo = projectRepository.findMaxProjnoByYearPrefix(year + "-"); // ex: 2025-003

    int nextSeq = 1;
    if (maxProjNo != null && maxProjNo.length() >= 8) {
      String[] parts = maxProjNo.split("-");
      if (parts.length == 2) {
        try {
          nextSeq = Integer.parseInt(parts[1]) + 1;
        } catch (NumberFormatException ignored) {
        }
      }
    }

    return String.format("%s-%03d", year, nextSeq); // ex: 2025-004
  }

  public static LocalDate parseFlexibleDate(String value) {
    try {
      if (value.matches(".*[Ee].*")) {
        double d = Double.parseDouble(value);
        int intVal = (int) d;
        return LocalDate.parse(String.valueOf(intVal), DateTimeFormatter.ofPattern("yyyyMMdd"));
      } else if (value.matches("^\\d{8}$")) {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd"));
      } else {
        return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("날짜 형식이 올바르지 않습니다: " + value);
    }
  }

  public static float tryFloat(String data) {
    if (!StringUtils.hasText(data)) {
      return 0;
    }

    try {
      // 숫자, 점, 음수만 남기고 나머지 제거
      data = data.replaceAll("[^0-9.\\-]", "");
      return Float.parseFloat(data);
    } catch (Exception e) {
      System.out.println("tryFloat: failed to parse [" + data + "]");
      return 0;
    }
  }


  // 수주 변환 changeSujuBulkData
  @PostMapping("/change")
  public AjaxResult changeSujuBulkData(
    @RequestParam MultiValueMap<String, Object> Q,
    HttpServletRequest request,
    Authentication auth) {

    AjaxResult result = new AjaxResult();

    User user = (User) auth.getPrincipal();

    List<Map<String, Object>> error_items = new ArrayList<>();
    String sql = "";

    List<Map<String, Object>> qItems = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());

    if (qItems.size() == 0) {
      result.success = false;
      return result;
    }

    for (int i = 0; i < qItems.size(); i++) {
      Integer id = Integer.parseInt(qItems.get(i).get("id").toString());
      String state = CommonUtil.tryString(qItems.get(i).get("state"));

      MapSqlParameterSource paramMap = new MapSqlParameterSource();
      paramMap.addValue("id", id);
      paramMap.addValue("user_pk", user.getId());

      //sujuUploadService.BeforeCheck();

      if (state.equals("엑셀")) {
        sql = """
            with A as (
                           select "JumunNumber", m.id as mat_pk, b."Quantity", b."JumunDate"::date, b."DueDate"::date, c.id as comp_pk, b."CompanyName"
                           , m."UnitPrice", case when m."VatExemptionYN" = 'Y' then 0 else 0.1 end as vat_pro
                           from suju_bulk b 
                           inner join material m on m."Code" = b."ProductCode"
                           --left join company c on c."Name" = b."CompanyName"
                           left join company c on c."Code"  = b."CompCode"
                           where b.id = :id
                       ), B as (
                           select A.mat_pk, A.comp_pk, mcu."UnitPrice"
                           , row_number() over (partition by A.mat_pk, A.comp_pk order by mcu."ApplyStartDate" desc) as g_idx
                           from mat_comp_uprice mcu
                           inner join A on A.mat_pk = mcu."Material_id"
                           and A.comp_pk = mcu."Company_id"
                           and A."JumunDate" between mcu."ApplyStartDate" and mcu."ApplyEndDate"
                       )
                       insert into suju("JumunNumber", "Material_id", "SujuQty", "SujuQty2", "JumunDate", "DueDate", "Company_id", "CompanyName"
                       , "UnitPrice", "Price", "Vat", "State", _status, _created, _creater_id )
                       select A."JumunNumber", A.mat_pk, A."Quantity", A."Quantity", A."JumunDate", A."DueDate", A.comp_pk, A."CompanyName"
                       , coalesce(B."UnitPrice", A."UnitPrice") as unit_price
                       , coalesce(B."UnitPrice", A."UnitPrice") * a."Quantity" as price
                       , A.vat_pro * coalesce(B."UnitPrice", A."UnitPrice") * a."Quantity" as vat
                       , 'received', 'excel', now(), :user_pk
                       from A 
                       left join B on B.mat_pk = a.mat_pk
                       and B.comp_pk = A.comp_pk 
                       and B.g_idx = 1
            """;
        this.sqlRunner.execute(sql, paramMap);

        sql = """
            update suju_bulk set _status = 'Suju' where id = :id
            """;

        this.sqlRunner.execute(sql, paramMap);

      } else {
        Map<String, Object> err_item = new HashMap<>();
        err_item.put("success", false);
        //err_item.put("message", "Excel상태만 전환할 수 있습니다.");
        err_item.put("id", id);
        error_items.add(err_item);
      }

    }

    result.success = true;

    if (error_items.size() > 0) {
      result.success = false;
      result.message = "엑셀 상태만 전환할 수 있습니다.";
    }

//		Map<String, Object> item = new HashMap<String, Object>();
//		item.put("error_items", error_items);
//
//		result.data=item;
    return result;
  }

  @Transactional
  @PostMapping("/force-complete")
  public AjaxResult forceCompleteSuju(@RequestBody Map<String, Object> payload) {
    AjaxResult result = new AjaxResult();

    List<Integer> sujuPkList = (List<Integer>) payload.get("sujuPkList");
    sujuRepository.forceCompleteSujuList(sujuPkList);
    return result;
  }

  @PostMapping("/save_Comp")
  public AjaxResult SaveComp(
    @RequestParam(value = "id", required = false) Integer id,   // ★ 신규일 땐 null 허용
    @RequestParam("name") String name,
    @RequestParam("cboCompanyType") String companyType,
    @RequestParam("TelNumber") String telNumber,
    @RequestParam("business_number") String businessNumber,
    @RequestParam("business_type") String businessType,
    @RequestParam("business_item") String businessItem,
    @RequestParam("address") String address,
    @RequestParam("fax_number") String fax_number,
    @RequestParam("sales_manager") String sales_manager,
    @RequestParam("email") String email,
    @RequestParam("spjangcd") String spjangcd,
    Authentication auth
  ) {
    AjaxResult result = new AjaxResult();
    User user = (User) auth.getPrincipal();
    try {
      Company company;

      if (id == null) {
        company = new Company();
        // 코드가 비어있으면 신규 코드 부여
        String compCode = sujuService.getNextCompCode();
        company.setCode(compCode);
      } else {
        company = this.companyRepository.getCompanyById(id);
        if (company == null) {
          result.success = false;
          result.message = "대상 회사가 존재하지 않습니다.";
          return result;
        }
        // 수정 시 코드가 없으면 보정(Optional)
        if (company.getCode() == null || company.getCode().isEmpty()) {
          company.setCode(sujuService.getNextCompCode());
        }
      }

      // 기본정보 세팅
      company.setName(name);
      company.setCompanyType(companyType);
      company.setTelNumber(telNumber);
      company.setBusinessNumber(businessNumber);
      company.setBusinessType(businessType);
      company.setBusinessItem(businessItem);
      company.setRelyn("0");
      company.setAddress(address);
      company.setFaxNumber(fax_number);
      company.setSalesManager(sales_manager);
      company.setEmail(email);
      company.setSpjangcd(spjangcd);
      company.set_audit(user);

      // 저장
      Company saved = companyRepository.save(company);

      // 프론트에서 바로 바인딩할 최소 데이터 제공
      Map<String, Object> data = new HashMap<>();
      data.put("id", saved.getId());
      data.put("name", saved.getName());

      result.success = true;
      result.message = "저장되었습니다.";
      result.data = data;
      return result;

    } catch (Exception e) {
      result.success = false;
      result.message = "저장 실패: " + e.getMessage();
      return result;
    }
  }

  @PostMapping("/save_material")
  public AjaxResult SaveMaterial(@RequestParam(value = "id", required = false) Integer id,
                                 @RequestParam("MaterialGroup_id") Integer MaterialGroup_id,
                                 @RequestParam("cboMaterialMid") Integer cboMaterialMid,
                                 @RequestParam("Name") String Name,
                                 @RequestParam("Unit_id") Integer Unit_id,
                                 @RequestParam(value = "Standard", required = false) String Standard,
                                 @RequestParam("Factory_id") Integer Factory_id,
                                 @RequestParam(value = "Thickness",required = false) Float Thickness,
                                 @RequestParam(value = "Width",  required = false) Float Width,
                                 @RequestParam(value = "Color",  required = false) String Color,
                                 @RequestParam("WorkCenter_id") Integer WorkCenter_id,
                                 @RequestParam("spjangcd") String spjangcd,
                                 Authentication auth
  ) {
    AjaxResult result = new AjaxResult();
    User user = (User) auth.getPrincipal();
    try {

      Material material;

      if (id == null) {
        material = new Material();
        // 코드가 비어있으면 신규 코드 부여
        String matCode = sujuService.getNextMatCode();
        material.setCode(matCode);
      } else {
        material = this.materialRepository.getMaterialById(id);
        if (material == null) {
          result.success = false;
          result.message = "대상 품목이 존재하지 않습니다.";
          return result;
        }
        if (material.getCode() == null || material.getCode().isEmpty()) {
          material.setCode(sujuService.getNextMatCode());
        }
      }

      material.setFactory_id(Factory_id);
      material.setName(Name);
      material.setMaterialGroupId(MaterialGroup_id);
      material.setUnitId(Unit_id);
      material.setStandard1(Standard);
      material.setSpjangcd(spjangcd);
      material.setThickness(Thickness); //폭
      material.setWidth(Width);
      material.setColor(Color);
      material.setUseyn("0");
      material.setWorkCenterId(WorkCenter_id);
//      if (Standard != null && !Standard.trim().isEmpty()) {
//        material.setRoutingId(11);
//      } else {
//        material.setRoutingId(10);
//      }
//      if (Objects.equals(WorkCenter_id, 46)) {
//        material.setRoutingId(11);
//      }
      material.setStoreHouseId(3);  // 자재창고가 기본으로
      material.setMatUserCode(cboMaterialMid);
      material.setPurchaseOrderStandard("mrp");
      material.setValidDays(1);
      material.set_audit(user);

      // 저장
      Material saved = materialRepository.save(material);

      createOrReuseDefaultBom(saved, spjangcd, user);

      String unitName = unitRepository.findById(Unit_id)
                          .map(Unit::getName)
                          .orElse(null);

      // 프론트에서 바로 바인딩할 최소 데이터 제공
      Map<String, Object> data = new HashMap<>();
      data.put("id", saved.getId());
      data.put("Code", saved.getCode());
      data.put("name", saved.getName());
      data.put("standard", saved.getStandard1());
      data.put("unit_name", unitName);
      data.put("GroupId", saved.getMaterialGroupId());

      result.success = true;
      result.message = "저장되었습니다.";
      result.data = data;
      return result;

    } catch (Exception e) {
      result.success = false;
      result.message = "저장 실패: " + e.getMessage();
      return result;
    }
  }

  private void createOrReuseDefaultBom(Material saved, String spjangcd, User user) {
    final String bomType = "manufacturing";
    final String version = "1.0";

    // 기간
    String startDateStr = java.time.LocalDate.now().toString() + " 00:00:00";
    String endDateStr = "2100-12-31 00:00:00";
    java.sql.Timestamp startTs = java.sql.Timestamp.valueOf(startDateStr);
    java.sql.Timestamp endTs = java.sql.Timestamp.valueOf(endDateStr);

    Integer bomId = null;

    // 1) 같은 Version 존재 여부
    boolean sameVer = bomService.checkSameVersion(null, saved.getId(), bomType, version);
    if (sameVer) {
      // 이미 있으면 끝 (필요 시 가져와서 사용)
      return;
    }

    // 2) 기간 중복 여부
    boolean dupPeriod = bomService.checkDuplicatePeriod(null, saved.getId(), bomType, startDateStr, endDateStr);
    if (dupPeriod) {
      // 기간 겹치면 StartDate만 'now'로 좁혀서 재시도
      startTs = new java.sql.Timestamp(System.currentTimeMillis());
    }

    // 3) 생성
    Bom bom = new Bom();
    bom.setName(saved.getName()); // 표시용(선택)
    bom.setMaterialId(saved.getId());
    bom.setOutputAmount(1F);
    bom.setBomType(bomType);
    bom.setVersion(version);
    bom.setStartDate(startTs);
    bom.setEndDate(endTs);
    bom.setSpjangcd(spjangcd);
    bom.set_audit(user);

    Bom savedBom = bomService.saveBom(bom);
    bomId = savedBom.getId();
    // 중복 검사
    boolean exists = bomService.checkDuplicateBomComponent(bomId, saved.getId());
    if (exists) return;

    BomComponent bc = new BomComponent();
    bc.setBomId(bomId);
    bc.setMaterialId(10853);
    bc.setAmount(1); // 필수
    bc.set_order(1);
    bc.setDescription(null);
    bc.setSpjangcd(spjangcd);
    bc.set_audit(user);

    bomService.saveBomComponent(bc);
  }

  @PostMapping("/estimate_confirm")
  @Transactional
  public AjaxResult estimateConfirm(
    @RequestParam("JumunNumber") String jumunNumber,
    Authentication auth
  ) {
    AjaxResult result = new AjaxResult();

    // 입력 검증
    if (jumunNumber == null || jumunNumber.isBlank()) {
      result.success = false;
      result.message = "주문번호가 없습니다.";
      return result;
    }

    // 1) 헤더 조회
    SujuHead head = (SujuHead) sujuHeadRepository.findByJumunNumber(jumunNumber)
                                 .orElse(null);
    if (head == null) {
      result.success = false;
      result.message = "수주 헤더를 찾을 수 없습니다.";
      return result;
    }

    // 2) 이미 확정된 건(= sales)이면 멱등 처리
    if ("sales".equalsIgnoreCase(head.getSujuType())

    ) {
      result.success = true;
      result.message = "이미 견적확정된 건입니다.";
      return result;
    }

    // 3) 확정 처리
    head.setSujuType("sales");


    sujuHeadRepository.save(head);

    // (선택) 디테일 일괄 동기화가 필요하면 벌크 업데이트 사용 권장
    // sujuRepository.bulkConfirmByHeadId(head.getId());

    result.success = true;
    result.message = "견적이 확정되었습니다.";
    return result;
  }

  @GetMapping("/print_list")
  public AjaxResult getPrintList( @RequestParam("id") int id){
    //log.info("견적서 인쇄 들어옴, id:{}", id);
    Map<String, Object> item = this.sujuService.getPrintList(id);

    AjaxResult result = new AjaxResult();
    result.data = item;

    return result;
  }

  @GetMapping("/workcenter_list")
  public AjaxResult getWorkcenterList( @RequestParam("factoryId") int Factory_id){

    List<Map<String, Object>> items = this.sujuService.getWorkcenterList(Factory_id);

    AjaxResult result = new AjaxResult();
    result.data = items;

    return result;
  }

  // ===================================================================
  // 프로젝트/수주 통합 등록 화면 전용 저장
  //  - 금액 필드 없음 (유니트→SujuQty, SET→Standard, 라인/설비타입→신규 컬럼)
  //  - Material_id 없는 행은 품목 get-or-create 후 수주 저장
  //
  //  ★ 라인 BOM / 외작 발주를 /excel_save 와 똑같이 동기화한다 (SujuSyncService).
  //     엑셀은 항상 신규 헤더라 전부 INSERT 가 되고, 이 화면은 저장할 때마다 diff 가 돈다.
  //     역추적 고리:
  //       BOM  : suju_line_bom(suju_head_id, line) → bom_id
  //       발주 : balju."PlanTableName"='suju' / "PlanDataPk"=suju.id
  //
  //     발주는 업체로 나가는 문서이므로 다음 규약을 지킨다. 임의로 완화하지 말 것.
  //       - 라인을 DELETE 하지 않는다. 되돌리기는 State='canceled' 상태전이로만.
  //       - "UnitPrice" 를 덮어쓰지 않는다. 발주 화면이 소유한다.
  //       - 입고(mat_inout)가 붙은 발주는 조용히 넘기지 않고 저장 자체를 막는다.
  // ===================================================================
  /**
   * 저장 전 발주 잠금 사전조회 (작업지시 수정 화면의 /edit_guard 와 같은 역할).
   *
   * <p>화면은 프로젝트/수주를 불러온 직후 이걸 호출해서
   *  - 발주가 걸린 행의 수량칸에 <code>min</code> 을 걸고
   *  - 제작업체 셀렉트를 잠그고
   *  - 사유를 안내줄에 띄운다.
   *
   * <p>발주가 하나도 없으면 빈 배열이 온다. 서버는 저장 시 같은 판정을 다시 하므로
   * 이 응답은 어디까지나 <b>미리 보여주기 위한 것</b>이고 방어선이 아니다.
   */
  @GetMapping("/balju_guard")
  public AjaxResult getBaljuGuard(@RequestParam("head_id") Integer headId) {
    AjaxResult result = new AjaxResult();
    result.success = true;
    result.data = sujuSyncService.getBaljuGuard(headId);
    return result;
  }

  @PostMapping("/manual_save_project")
  @Transactional
  public AjaxResult SujuSaveProject(@RequestBody Map<String, Object> payload, Authentication auth) {
    User user = (User) auth.getPrincipal();
    AjaxResult result = new AjaxResult();

    Date jumunDate = CommonUtil.trySqlDate((String) payload.get("JumunDate"));
    Date dueDate   = CommonUtil.trySqlDate((String) payload.get("DueDate"));
    String spjangcd = (String) payload.get("spjangcd");
    String sujuType = (String) payload.get("SujuType");
    String description = (String) payload.get("Description");
    Integer orderId = toIntegerOrNull(payload.get("order_id"));
    String orderName = (String) payload.get("OrderName");
    String projno = (String) payload.get("projno");

    if (orderId == null) {
      result.success = false;
      result.message = "수주처가 지정되지 않았습니다.";
      return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
    if (items == null) items = new ArrayList<>();

    // ---------- JIG SET 사전 검증 ----------
    //  ★ 반드시 헤더 save() 앞에 둔다. 뒤로 가면 rollbackOnly 처리가 필요해진다.
    //  ★ JIG SET 이 곧 생산 수량이므로 빈 값을 0 으로 넘기면 그 품목이
    //    생산지시·BOM·발주에서 통째로 사라진다. 저장 자체를 막는다.
    List<String[]> jigCheck = new ArrayList<>();
    for (Map<String, Object> it : items) {
      String nm = str(it.get("txtProductName"));
      if (nm.isEmpty()) continue;                    // 빈 행은 아래 저장 루프에서도 skip 된다
      jigCheck.add(new String[]{ str(it.get("setCnt")), nm });
    }
    String jigError = JigSetParser.validateAll(jigCheck);
    if (jigError != null) {
      result.success = false;
      result.message = jigError;
      return result;
    }

    // ---------- 헤더 (신규/수정) ----------
    SujuHead head;
    if (payload.get("id") != null && !payload.get("id").toString().isEmpty()) {
      head = sujuHeadRepository.findById(Integer.parseInt(payload.get("id").toString()))
               .orElse(new SujuHead());
    } else {
      head = new SujuHead();
      head.setJumunNumber(generateJumunNumber(jumunDate));
    }
    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(orderId);
    head.setSpjangcd(spjangcd);
    head.setSujuType(sujuType);
    head.setDescription(description);
    head.setSujuName(nullIfEmpty(payload.get("suju_name")));
    head.setProjectId(nullIfEmpty(projno));
    head.setSuJuOrderId(orderId);
    head.setSuJuOrderName(orderName);
    head.set_status("manual");
    head.set_audit(user);
    head = sujuHeadRepository.save(head);

    // ---------- 삭제 동기화 (payload에 없는 기존 라인 제거) ----------
    List<Integer> incomingIds = new ArrayList<>();
    for (Map<String, Object> it : items) {
      Integer sid = toIntegerOrNull(it.get("suju_id"));
      if (sid != null && sid > 0) incomingIds.add(sid);
    }
    for (Suju ex : SujuRepository.findBySujuHeadId(head.getId())) {
      if (ex.getId() != null && !incomingIds.contains(ex.getId())) {
        // 발주가 걸려 있으면 취소로 정리한다. 입고가 진행됐으면 삭제 자체를 막는다.
        String blocked = sujuSyncService.blockOrCancelBaljuBeforeDelete(
          ex.getId(), ex.getMaterial_Name(), user);
        if (blocked != null) {
          result.success = false;
          result.message = blocked;
          TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
          return result;
        }
        suJuDetailRepository.deleteBySujuId(ex.getId());
        SujuRepository.deleteById(ex.getId());
      }
    }

    // ---------- 신규 품목에 남길 출처 메모 ----------
    //  프로젝트 1회성 품목이라 마스터만 보면 어디서 왔는지 알 수 없다.
    //  루프 밖에서 1번만 조회한다 (행마다 tb_da003 을 때리지 않게).
    String matSourceMemo = buildMaterialSourceMemo(
      projno, findProjectNameByNo(projno, spjangcd), head.getSujuName());

    // ---------- 라인 저장 ----------
    for (Map<String, Object> item : items) {
      String productName = str(item.get("txtProductName"));
      if (productName.isEmpty()) continue;

      // ★ 품목 확정 (없으면 생성)
      Integer materialId = resolveOrCreateMaterialForProject(item, spjangcd, user, matSourceMemo);

      Suju suju;
      Integer sujuId = toIntegerOrNull(item.get("suju_id"));
      if (sujuId != null && sujuId > 0) {
        suju = SujuRepository.findById(sujuId).orElse(new Suju());
      } else {
        suju = new Suju();
        suju.setJumunNumber(head.getJumunNumber());
      }

      suju.setSujuHeadId(head.getId());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(orderId);
      suju.setCompanyName(orderName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.setConfirm("0");
      suju.set_audit(user);

      suju.setPinShiftUnit(str(item.get("pinShiftUnit")));
      suju.setLegSpec(str(item.get("legSpec")));
      suju.setLegCnt(str(item.get("legCnt")));
      suju.setMakeType(str(item.get("makeType")));
      suju.setDesignCompId(toIntegerOrNull(item.get("designCompId")));
      suju.setDesignCompName(str(item.get("designCompName")));
      suju.setDrawDate(CommonUtil.trySqlDate(str(item.get("drawDate"))));
      suju.setMakeCompId(toIntegerOrNull(item.get("makeCompId")));
      suju.setMakeCompName(str(item.get("makeCompName")));
      suju.setItemRemark(str(item.get("itemRemark")));

      suju.setMaterialId(materialId);
      suju.setMaterial_Name(productName);
      suju.setProject_id(projno);

      // ★ JIG SET → "SujuQty"(환산값, NOT NULL 2개) + "Standard"(원문 보존)
      //   유니트 → unit_qty
      //
      //   생산은 유니트가 아니라 JIG SET 수량으로 한다.
      //   예) A16 : JIG SET "1/1" → 2벌 생산 / 유니트 8 은 그 지그가 담당하는 유니트 수.
      //   "Standard" 에 원문을 남겨야 환산 규칙이 바뀌어도 다시 계산할 수 있다.
      //   위에서 사전 검증했으므로 여기서는 예외가 나지 않는다.
      String jigSetRaw = str(item.get("setCnt"));
      double jigSetQty = JigSetParser.parse(jigSetRaw, productName);
      suju.setSujuQty(jigSetQty);
      suju.setSujuQty2(jigSetQty);
      suju.setStandard(jigSetRaw);
      suju.setUnitQty(toIntegerOrNull(item.get("unit")));

      // 라인 / 설비타입 → 신규 컬럼 (Suju 엔티티에 필드 있어야 함)
      suju.setLine(nullIfEmpty(item.get("line")));
      suju.setEquipType(nullIfEmpty(item.get("equiptype")));   // 텍스트 그대로 (user_code 변환 없음)

      SujuRepository.save(suju);
    }

    // ---------- 라인 BOM / 외작 발주 동기화 ----------
    //  suju 저장이 끝난 뒤 DB 를 다시 읽어 diff 를 돌린다. 엑셀 업로드와 같은 경로.

    //  ★ flush 필수.
    //    SujuRepository.save() 는 JPA 라 영속성 컨텍스트에만 올려두고 UPDATE 를
    //    커밋 시점에 날린다. SujuSyncService 는 SqlRunner(순수 JDBC) 로 suju 를
    //    다시 읽으므로, flush 하지 않으면 방금 저장한 값이 아니라 옛 값을 본다.
    //    (유니트를 8→12 로 고쳐도 발주가 8 그대로 남는 증상)
    SujuRepository.flush();

    SujuSyncService.SyncResult bomRes =
      sujuSyncService.syncLineBoms(head.getId(), spjangcd, user);

    SujuSyncService.SyncResult baljuRes = sujuSyncService.syncBalju(
      head.getId(), jumunDate, dueDate, spjangcd, user,
      buildBaljuSourceMemo(head.getJumunNumber(), projno, head.getSujuName()));

    // ── WBS 2D 출도 반영 ──
    //  이 수주 품목들의 draw_date 중 가장 늦은 날을 WBS 의 '2D 출도' 단계 완료일로 넣는다.
    //  대상은 auto_source='draw_date' 로 표시된 행뿐이다. WBS 확정 전이면 대상이 없어
    //  아무 일도 하지 않는다. WBS 는 부가 정보이므로 여기서 실패해도 수주 저장은 막지 않는다.
    try {
      wbsPlanService.syncDrawDate(head.getId(), user);
    } catch (Exception e) {
      log.warn("[WBS] 도면출도일 반영 실패 (수주 저장은 계속): head={} {}", head.getId(), e.getMessage());
    }

    if (!baljuRes.ok) {
      result.success = false;
      result.message = baljuRes.message;
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    Map<String, Object> data = new HashMap<>();
    data.put("headId", head.getId());
    data.put("bomCount", bomRes.bomCreated + bomRes.bomUpdated + bomRes.bomRemoved);
    data.put("baljuCount", baljuRes.baljuInserted + baljuRes.baljuUpdated + baljuRes.baljuCanceled);
    result.data = data;
    result.message = "저장되었습니다." + bomRes.summary() + baljuRes.summary();
    result.success = true;
    return result;
  }

  // ---------- 신규 품목 채번 (BOM 생성 제외) ----------
  //  엑셀 업로드와 동일하게 이름으로 기존 품목을 재사용하지 않는다.
  //  단, 화면에서 이미 품목을 지정했거나 기존 행을 수정하는 경우(Material_id 존재)는 그대로 쓴다.
  private Integer resolveOrCreateMaterialForProject(Map<String, Object> item, String spjangcd, User user,
                                                    String sourceMemo) {
    // 1) 이미 id 있으면 그대로
    Integer mid = toIntegerOrNull(item.get("Material_id"));
    if (mid != null) return mid;

    String name = str(item.get("txtProductName"));
    if (name.isEmpty()) {
      throw new IllegalArgumentException("품목명이 비어 있습니다.");
    }

    // 2) 품목그룹 → MaterialGroup_id (엑셀 업로드와 동일하게 MAKE 고정)
    Integer groupId = findMaterialGroupIdByCode(PROC_MATERIAL_GROUP_CODE);
    if (groupId == null) {
      throw new IllegalStateException(
        "품목그룹을 찾을 수 없습니다. mat_grp 에 Code='" + PROC_MATERIAL_GROUP_CODE + "' 인 행이 필요합니다.");
    }

    // 3) factory_id (라인에서 추출, 없으면 1)
    Integer factoryId = toIntegerOrNull(item.get("factoryId"));
    if (factoryId == null) factoryId = 1;

    Integer workcenterId = toIntegerOrNull(item.get("lineId"));
    Integer unitId = toIntegerOrNull(item.get("unitId"));
    // 설비타입(장비)은 user_code 로 변환하지 않는다. suju.equip_type 텍스트로만 저장.

    // 4) 신규 INSERT (BOM 생성 안 함)
    Material material = new Material();
    material.setCode(sujuService.getNextMatCode());
    material.setName(name);
    material.setMaterialGroupId(groupId);
    material.setFactory_id(factoryId);
    material.setUnitId(unitId);
    material.setSpjangcd(spjangcd);
    material.setUseyn("0");
    material.setWorkCenterId(workcenterId);
    material.setStoreHouseId(3);
    material.setPurchaseOrderStandard("mrp");
    material.setValidDays(1);
    material.setDescription(sourceMemo);   // 프로젝트 / 수주명
    // mat_user_code 는 NULL 허용. 장비를 여기에 넣던 코드 제거.
    material.set_audit(user);

    Material saved = materialRepository.save(material);
    return saved.getId();
  }

  // ===================================================================
  //  수주 엑셀 업로드 양식(템플릿) 다운로드
  //   {DOC_TEMPLATE_PATH} 폴더를 그대로 나열해서 내려준다.
  //
  //   ★ 파일명을 코드에 박지 않는다. 양식이 늘거나 이름이 바뀌어도
  //     폴더에 넣기만 하면 화면 목록에 뜬다 — 재배포도, 코드 수정도 없다.
  //     (예전에는 form=make|qty 로 파일명 두 개를 상수에 박아뒀는데,
  //      한글 파일명으로 바꾸는 순간 404 가 나는 구조였다)
  // ===================================================================

  /**
   * 양식 목록.
   *
   *  폴더가 없거나 비었으면 빈 배열을 돌려준다. 화면은 "없습니다" 로만 표시한다 —
   *  서버 경로를 화면에 노출하지 않는다. 원인은 서버 로그에서 본다.
   */
  @GetMapping("/excel_template_list")
  public AjaxResult excelTemplateList() {
    AjaxResult result = new AjaxResult();
    result.data = listTemplateFiles();
    result.success = true;
    return result;
  }

  /**
   * 양식 내려받기.
   *
   *  ★ name 으로 경로를 조립하지 않는다. 폴더를 다시 읽어 <b>목록에 실제로 있는
   *    파일</b>과 대조한 뒤 그 Path 를 쓴다. 조립하면 {@code ../application.yml}
   *    같은 값이 그대로 통한다.
   */
  @GetMapping("/excel_template")
  public void excelTemplate(@RequestParam(value = "name", required = false) String name,
                            HttpServletResponse response) throws IOException {

    String target = (name == null) ? "" : name.trim();
    if (target.isEmpty()) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "양식을 지정하세요.");
      return;
    }

    Path file = null;
    for (Map<String, Object> f : listTemplateFiles()) {
      if (target.equals(f.get("name"))) {
        file = Paths.get(String.valueOf(f.get("path")));
        break;
      }
    }

    if (file == null || !Files.isReadable(file)) {
      log.warn("[excel_template] 요청한 양식이 폴더에 없습니다: {} (dir={})", target, DOC_TEMPLATE_PATH);
      response.sendError(HttpServletResponse.SC_NOT_FOUND,
        "양식 파일이 서버에 없습니다. 관리자에게 문의하세요.");
      return;
    }

    String fileName = file.getFileName().toString();

    // 한글 파일명은 그냥 넣으면 브라우저가 깨뜨린다.
    //  filename* (RFC 5987) 를 쓰고, 못 읽는 브라우저용으로 filename 도 같이 준다.
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

    response.setContentType(fileName.toLowerCase(Locale.ROOT).endsWith(".xls")
                              ? "application/vnd.ms-excel"
                              : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition",
      "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
    response.setContentLengthLong(Files.size(file));

    Files.copy(file, response.getOutputStream());
    response.getOutputStream().flush();
  }

  /** 템플릿 폴더의 엑셀 파일 목록. 폴더가 없으면 빈 리스트 */
  private List<Map<String, Object>> listTemplateFiles() {
    List<Map<String, Object>> rows = new ArrayList<>();

    Path dir = Paths.get(DOC_TEMPLATE_PATH).normalize();
    if (!Files.isDirectory(dir)) {
      // 배포 환경마다 흔한 상황이라 error 가 아니라 warn 으로 남긴다
      log.warn("[excel_template] 양식 폴더가 없습니다: {}", dir);
      return rows;
    }

    DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
      for (Path f : ds) {
        if (!Files.isRegularFile(f)) continue;

        String fileName = f.getFileName().toString();
        // 엑셀이 열어둔 임시파일(~$xxx.xlsx)이 목록에 뜨는 것을 막는다
        if (fileName.startsWith("~$")) continue;

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) continue;
        if (!TEMPLATE_EXT.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))) continue;

        long size = Files.size(f);
        Map<String, Object> row = new HashMap<>();
        row.put("name", fileName);
        row.put("path", f.toString());
        row.put("size", size);
        row.put("size_text", humanSize(size));
        row.put("modified", ymd.format(Files.getLastModifiedTime(f).toInstant()));
        rows.add(row);
      }
    } catch (IOException e) {
      log.warn("[excel_template] 양식 폴더를 읽지 못했습니다: {}", dir, e);
      return new ArrayList<>();
    }

    rows.sort(Comparator.comparing(r -> String.valueOf(r.get("name"))));
    return rows;
  }

  private static String humanSize(long bytes) {
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
    return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
  }

  /**
   * 업로드한 엑셀 원본을 보관한다.
   *  {DOC_PATH}/upload/{수주번호}_{원본파일명}
   *  수주번호를 앞에 붙여 중복을 막고 눈으로도 찾을 수 있게 한다.
   *  원본 파일명은 사용자가 올린 그대로(한글 포함) 두되, 윈도우 금지문자만 걷어낸다.
   *  보관 실패가 수주 저장을 막으면 안 되므로 예외를 삼키고 로그만 남긴다.
   */
  private void archiveUploadedExcel(MultipartFile file, String jumunNumber) {
    if (file == null || file.isEmpty()) return;
    try {
      String origin = file.getOriginalFilename();
      if (origin == null || origin.isBlank()) origin = "upload.xlsx";
      // 경로 구분자가 섞여 들어오는 브라우저가 있어 파일명만 남긴다
      origin = Paths.get(origin.replace('\\', '/')).getFileName().toString();
      // 윈도우에서 파일명에 못 쓰는 문자 제거 (\ / : * ? " < > |)
      origin = origin.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
      if (origin.isEmpty()) origin = "upload.xlsx";

      Path dir = Paths.get(DOC_UPLOAD_PATH);
      Files.createDirectories(dir);

      Path target = dir.resolve(jumunNumber + "_" + origin);
      // 같은 수주에 같은 이름으로 재업로드하면 덮어쓴다 (최신본 유지)
      Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

      log.info("[excel_save] 업로드 원본 보관: {}", target);
    } catch (Exception e) {
      log.error("[excel_save] 업로드 원본 보관 실패. jumunNumber={}", jumunNumber, e);
    }
  }

  // ===================================================================
//  수주 엑셀 업로드 - 즉시 저장
//   파일 + 헤더(수주처/프로젝트/수주일/납기일) 받아
//   파싱 → 공정(품목) → 수주 라인 → 라인별 BOM → 외작 발주까지 한 번에 저장
// ===================================================================
  /**
   * 엑셀 미리보기. <b>저장하지 않고</b> A1 제목과 양식명만 돌려준다.
   *
   * <p>파일을 고를 때 화면의 수주명 칸을 채우기 위한 것.
   *
   * <p>이게 없으면 <b>덮어쓰기가 영원히 발동하지 않는다.</b>
   * {@code /project_suju_list} 의 name_matched 판정은 화면 입력값만 보는데,
   * 수주명을 사람이 안 치면 항상 빈 문자열이라 매칭이 실패한다.
   * 그런데 실제 저장은 A1 에서 뽑은 이름으로 들어가서,
   * 확인창과 저장이 서로 다른 이름을 보게 된다.
   * (실제로 NQ6 을 재업로드해도 매번 "새로 등록" 으로 빠졌다)
   */
  @PostMapping("/excel_peek")
  public AjaxResult excelPeek(@RequestParam("upload_file") MultipartFile file) {
    AjaxResult result = new AjaxResult();
    try {
      Map<String, Object> parsed = parseSujuExcel(file);
      Map<String, Object> data = new HashMap<>();
      data.put("projectTitle", parsed.get("projectTitle"));   // "NQ10 제작출도리스트" → "NQ10"
      data.put("formName", parsed.get("formName"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> its = (List<Map<String, Object>>) parsed.get("items");
      data.put("itemCount", its == null ? 0 : its.size());
      result.success = true;
      result.data = data;
    } catch (Exception e) {
      // 미리보기 실패로 업로드를 막지는 않는다. 저장 때 같은 오류로 다시 걸린다.
      log.warn("[excel_peek] 미리보기 실패: {}", e.getMessage());
      result.success = false;
      result.message = e.getMessage();
    }
    return result;
  }

  @PostMapping("/excel_save")
  @Transactional
  public AjaxResult excelSave(
    @RequestParam("upload_file") MultipartFile file,
    @RequestParam(value = "spjangcd", required = false) String spjangcd,
    @RequestParam("order_id") Integer orderId,
    @RequestParam("OrderName") String orderName,
    @RequestParam("JumunDate") String jumunDateStr,
    @RequestParam("DueDate") String dueDateStr,
    @RequestParam("projno") String projno,
    @RequestParam(value = "suju_name", required = false) String sujuName,
    @RequestParam(value = "overwrite_head_id", required = false) Integer overwriteHeadId,
    Authentication auth) {

    AjaxResult result = new AjaxResult();

    // ── 0) 검증 ──
    if (file == null || file.isEmpty()) {
      result.success = false; result.message = "파일이 없습니다."; return result;
    }
    if (orderId == null) {
      result.success = false; result.message = "수주처가 지정되지 않았습니다."; return result;
    }
    if (projno == null || projno.trim().isEmpty()) {
      result.success = false; result.message = "프로젝트가 지정되지 않았습니다."; return result;
    }

    User user = (User) auth.getPrincipal();
    Date jumunDate = CommonUtil.trySqlDate(jumunDateStr);
    Date dueDate   = CommonUtil.trySqlDate(dueDateStr);

    // ── 0-1) spjangcd 정규화 ──
    // suju_head.spjangcd / suju.spjangcd 는 varchar(2).
    // 파라미터가 중복 전송되면 "ZZ,ZZ" 로 바인딩돼 22001(too long) 이 난다.
    log.info("[excel_save] 수신 spjangcd = [{}]", spjangcd);
    spjangcd = firstToken(spjangcd);
    if (spjangcd != null && spjangcd.length() > 2) {
      result.success = false;
      result.message = "사업장코드가 올바르지 않습니다: [" + spjangcd + "]";
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    // ── 1) 엑셀 파싱 ──
    List<Map<String, Object>> items;
    String formName;
    String projectTitle;
    try {
      Map<String, Object> parsed = parseSujuExcel(file);
      formName = (String) parsed.get("formName");
      projectTitle = (String) parsed.get("projectTitle");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> parsedItems = (List<Map<String, Object>>) parsed.get("items");
      items = parsedItems;
    } catch (Exception e) {
      result.success = false;
      result.message = e.getMessage();
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    if (items == null || items.isEmpty()) {
      result.success = false;
      result.message = "읽어들인 데이터가 없습니다. 양식을 확인하세요.";
      return result;
    }

    // ── JIG SET 사전 검증 ──
    //  엑셀은 화면 검증을 우회하는 유일한 입구이므로 여기서도 같은 파서를 태운다.
    //  ★ 헤더 생성/덮어쓰기 삭제보다 앞에 둔다 (롤백 없이 빠져나가기 위해).
    //  ★ 엑셀은 몇 번째 행인지 알려주지 않으면 사용자가 찾지 못한다.
    {
      List<String[]> jigCheck = new ArrayList<>();
      int jigRowNo = 0;
      for (Map<String, Object> it : items) {
        jigRowNo++;
        String nm = str(it.get("procName"));
        if (nm.isEmpty()) continue;
        jigCheck.add(new String[]{ str(it.get("jigSet")), jigRowNo + "행 " + nm });
      }
      String jigError = JigSetParser.validateAll(jigCheck);
      if (jigError != null) {
        result.success = false;
        result.message = jigError;
        return result;
      }
    }

    // ── 1-1) 덮어쓰기 ──
    //  화면이 /project_suju_list 로 미리 물어보고, 사용자가 [덮어쓰기] 를 고른 경우에만
    //  overwrite_head_id 가 온다. 기존 수주를 정리한 뒤 아래에서 새로 넣는다.
    //
    //  ★ 삭제 규칙은 /delete 와 같은 deleteSujuHeadCore 를 쓴다.
    //    입고된 발주나 실적이 찍힌 작업지시가 있으면 여기서 막히고 업로드 전체가 롤백된다.
    //
    //  ★ 수주번호는 유지되지 않는다. 기존 헤더를 지우고 새로 채번하므로
    //    20260820-1686 → 20260826-**** 로 바뀐다. 유지가 필요하면 별도 논의.
    int overwriteRemovedLines = 0;
    if (overwriteHeadId != null) {
      // 다른 프로젝트의 수주를 실수로 지우지 않도록 소속을 확인한다.
      String belongs = queryStringSafe(
        "SELECT project_id FROM suju_head WHERE id = :id",
        new MapSqlParameterSource().addValue("id", overwriteHeadId));
      if (belongs == null || !belongs.trim().equals(projno.trim())) {
        result.success = false;
        result.message = "덮어쓸 수주가 이 프로젝트에 속하지 않습니다. 화면을 새로고침한 뒤 다시 시도하세요.";
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return result;
      }

      overwriteRemovedLines = SujuRepository.findBySujuHeadId(overwriteHeadId).size();

      HeadDeleteResult del = deleteSujuHeadCore(overwriteHeadId, user);
      if (!del.ok) {
        result.success = false;
        result.message = "기존 수주를 정리하지 못해 업로드를 중단했습니다.\n\n" + del.message;
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return result;
      }
      log.info("[excel_save] 덮어쓰기: 기존 head={} ({}행) 정리{}",
        overwriteHeadId, overwriteRemovedLines, del.summary());
    }

    // ── 2) 수주 헤더 생성 ──
    SujuHead head = new SujuHead();
    head.setJumunNumber(generateJumunNumber(jumunDate));
    head.setJumunDate(jumunDate);
    head.setDeliveryDate(dueDate);
    head.setCompany_id(orderId);
    head.setSpjangcd(spjangcd);
    head.setSujuType("sales");
    // 주의: suju_head."Description" 은 화면의 '비고' 가 사용 중이므로 프로젝트명을 넣지 않는다.
    // 수주 구분명: 화면 입력값 우선, 비면 A1 제목에서 뽑은 값
    String headName = nullIfEmpty(sujuName);
    if (headName == null) headName = projectTitle;
    head.setSujuName(headName);
    head.setProjectId(projno);
    head.setSuJuOrderId(orderId);
    head.setSuJuOrderName(orderName);
    head.set_status("manual");
    head.set_audit(user);
    head = sujuHeadRepository.save(head);

    // ── 3) 공정(품목) → 수주 라인 저장 ──
    //  중분류(user_code) 는 사용하지 않는다. 라인은 suju."line" 텍스트로 보관하고,
    //  BOM 등록용으로 라인별 구성품 목록을 함께 모은다.
    Map<String, Integer> compCache = new HashMap<>();   // 업체명 → 매입처 id (null 결과도 캐시)
    int saved = 0;

    // 신규 품목에 남길 출처 메모 (루프 밖에서 1번만)
    String matSourceMemo = buildMaterialSourceMemo(
      projno, findProjectNameByNo(projno, spjangcd), head.getSujuName());

    for (Map<String, Object> it : items) {
      String lineName = str(it.get("line"));
      String procName = str(it.get("procName"));
      if (procName.isEmpty()) continue;

      // 행마다 품목 신규 채번 (재사용 안 함)
      Integer matId = createMaterial(procName, PROC_MATERIAL_GROUP_CODE, spjangcd, user, matSourceMemo);

      Suju suju = new Suju();
      suju.setSujuHeadId(head.getId());
      suju.setJumunNumber(head.getJumunNumber());
      suju.setJumunDate(jumunDate);
      suju.setDueDate(dueDate);
      suju.setCompanyId(orderId);
      suju.setCompanyName(orderName);
      suju.setSpjangcd(spjangcd);
      suju.set_status("manual");
      suju.setState("received");
      suju.setConfirm("0");
      suju.set_audit(user);

      suju.setMaterialId(matId);
      suju.setMaterial_Name(procName);
      suju.setProject_id(projno);

      // ★ JIG SET → "SujuQty"(환산값, NOT NULL 2개) + "Standard"(원문 보존)
      //   유니트 → unit_qty.  /manual_save_project 와 동일 규칙.
      String jigSetRaw = str(it.get("jigSet"));
      double jigSetQty = JigSetParser.parse(jigSetRaw, procName);
      suju.setSujuQty(jigSetQty);
      suju.setSujuQty2(jigSetQty);
      suju.setStandard(jigSetRaw);
      suju.setUnitQty(toIntegerOrNull(it.get("unitQty")));

      // 라인 / 설비타입 → 신규 컬럼
      suju.setLine(nullIfEmpty(lineName));
      suju.setEquipType(nullIfEmpty(it.get("equipType")));   // 엑셀 '장비' / '설비 타입' 텍스트 그대로

      // 설계업체 / 제작업체 → 매입처(company.CompanyType='purchase') 조회
      String designCompName = str(it.get("designCompName"));
      String makeCompName   = str(it.get("makeCompName"));
      suju.setDesignCompId(findPurchaseCompanyIdByName(designCompName, compCache, spjangcd, user));
      suju.setDesignCompName(nullIfEmpty(designCompName));
      Integer makeCompId = findPurchaseCompanyIdByName(makeCompName, compCache, spjangcd, user);
      suju.setMakeCompId(makeCompId);
      suju.setMakeCompName(nullIfEmpty(makeCompName));

      // 제작구분: '일진' 이면 내작, 다른 업체면 외작
      String makeType = resolveMakeType(makeCompName, designCompName);
      suju.setMakeType(makeType);

      // 도면출도일 / 비고 / 나머지 수치 컬럼
      suju.setDrawDate(CommonUtil.trySqlDate(str(it.get("drawDate"))));
      suju.setItemRemark(nullIfEmpty(it.get("itemRemark")));
      suju.setPinShiftUnit(nullIfEmpty(it.get("pinShiftUnit")));
      suju.setLegSpec(nullIfEmpty(it.get("legSpec")));
      suju.setLegCnt(nullIfEmpty(it.get("legCnt")));

      SujuRepository.save(suju);
      saved++;
      // BOM / 발주 적재는 하지 않는다. 아래 SujuSyncService 가 DB 를 다시 읽어 처리한다.
    }

    if (saved == 0) {
      result.success = false;
      result.message = "저장할 공정 데이터가 없습니다.";
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    // ── 4) 라인 BOM / 외작 발주 등록 ──
    //  수동 등록(/manual_save_project)과 똑같이 SujuSyncService 를 탄다.
    //  여기서는 항상 신규 헤더이므로 diff 결과가 전부 INSERT 가 된다.
    //  발주 비고(balju_head."Description")에 출처를 남겨 자동생성 건임을 구분한다.

    //  ★ flush 필수.
    //    SujuRepository.save() 는 JPA 라 영속성 컨텍스트에만 올려두고 UPDATE 를
    //    커밋 시점에 날린다. SujuSyncService 는 SqlRunner(순수 JDBC) 로 suju 를
    //    다시 읽으므로, flush 하지 않으면 방금 저장한 값이 아니라 옛 값을 본다.
    //    (유니트를 8→12 로 고쳐도 발주가 8 그대로 남는 증상)
    SujuRepository.flush();

    SujuSyncService.SyncResult bomRes =
      sujuSyncService.syncLineBoms(head.getId(), spjangcd, user);

    SujuSyncService.SyncResult baljuRes = sujuSyncService.syncBalju(
      head.getId(), jumunDate, dueDate, spjangcd, user,
      buildBaljuSourceMemo(head.getJumunNumber(), projno, head.getSujuName()));

    // ── WBS 2D 출도 반영 ──
    //  이 수주 품목들의 draw_date 중 가장 늦은 날을 WBS 의 '2D 출도' 단계 완료일로 넣는다.
    //  대상은 auto_source='draw_date' 로 표시된 행뿐이다. WBS 확정 전이면 대상이 없어
    //  아무 일도 하지 않는다. WBS 는 부가 정보이므로 여기서 실패해도 수주 저장은 막지 않는다.
    try {
      wbsPlanService.syncDrawDate(head.getId(), user);
    } catch (Exception e) {
      log.warn("[WBS] 도면출도일 반영 실패 (수주 저장은 계속): head={} {}", head.getId(), e.getMessage());
    }

    if (!baljuRes.ok) {
      result.success = false;
      result.message = baljuRes.message;
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      return result;
    }

    int bomCount   = bomRes.bomCreated + bomRes.bomUpdated;
    int baljuCount = baljuRes.baljuHeadCreated;

    // ── 6) 업로드 원본 보관 ──
    //  실패해도 수주 저장은 그대로 둔다 (내부에서 예외를 삼키고 로그만 남긴다)
    archiveUploadedExcel(file, head.getJumunNumber());

    // ── 6) 결과 ──
    Map<String, Object> data = new HashMap<>();
    data.put("formName", formName);
    data.put("projectTitle", projectTitle);   // A1 에서 추출한 값 (화면 프리필용)
    data.put("savedCount", saved);
    data.put("bomCount", bomCount);
    data.put("baljuCount", baljuCount);
    data.put("headId", head.getId());
    result.success = true;
    result.message = formName + " " + saved + "건이 저장되었습니다."
                       + (overwriteHeadId != null
                            ? " (기존 " + overwriteRemovedLines + "행을 덮어썼습니다)" : "")
                       + " (BOM " + bomCount + "건, 발주 " + baljuCount + "건)";
    result.data = data;
    return result;
  }

  /** 단일 문자열 조회. 결과가 없으면 null. */
  private String queryStringSafe(String sql, MapSqlParameterSource p) {
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getString(1));
    } catch (Exception e) {
      return null;
    }
  }

  // createBaljuForOutsource / createLineBoms 는 SujuSyncService 로 옮겼다.
  //  수동 등록과 엑셀 업로드가 같은 동기화 로직을 쓰게 하기 위한 것으로,
  //  여기에 다시 만들지 말 것. (중복되면 두 화면의 동작이 곧 갈라진다)

  /**
   * 수주에서 자동생성된 발주임을 나타내는 비고 문구.
   *   수주 20260821-0001 / P2026-001 / 3호기 지그
   *
   * balju_head."Description" / balju."Description" 모두 varchar(500).
   * 사람이 발주 화면에서 읽는 용도이며, 담당자가 편집할 수 있으므로
   * 이 문자열을 파싱해서 수주를 역추적하지 말 것.
   * 기계 역추적은 balju."PlanDataPk"(= suju.id) → "BaljuHead_id" 경로를 쓴다.
   */
  private static final int BALJU_DESC_MAX = 500;

  private static final int MATERIAL_DESC_MAX = 500;

  /**
   * 품목이 어느 프로젝트/수주에서 생겼는지 남기는 메모.
   *   P2026-001 3호기 지그 / 수주 2차 발주분
   *
   * material."Description" 은 varchar(500) 이고 사람이 품목 화면에서 읽는 용도다.
   * 담당자가 편집할 수 있으므로 이 문자열을 파싱해서 프로젝트를 역추적하지 말 것.
   * 기계 역추적은 suju."Material_id" → suju.project_id 경로를 쓴다.
   */
  private String buildMaterialSourceMemo(String projno, String projnm, String sujuName) {
    StringBuilder sb = new StringBuilder();
    if (projno != null && !projno.trim().isEmpty()) {
      sb.append(projno.trim());
    }
    if (projnm != null && !projnm.trim().isEmpty()) {
      if (sb.length() > 0) sb.append(' ');
      sb.append(projnm.trim());
    }
    if (sujuName != null && !sujuName.trim().isEmpty()) {
      if (sb.length() > 0) sb.append(" / ");
      sb.append("수주 ").append(sujuName.trim());
    }
    // 프로젝트도 수주명도 없으면 굳이 빈 문자열을 넣지 않는다.
    return sb.length() == 0 ? null : cut(sb.toString(), MATERIAL_DESC_MAX);
  }

  /** projno → tb_da003.projnm. 없으면 null (메모는 부가정보이므로 실패해도 저장은 계속). */
  private String findProjectNameByNo(String projno, String spjangcd) {
    if (projno == null || projno.trim().isEmpty()) return null;
    String sql = "SELECT projnm FROM tb_da003 "
                   + "WHERE projno = :projno AND spjangcd = :spjangcd LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource()
                                .addValue("projno", projno.trim())
                                .addValue("spjangcd", spjangcd);
    return queryStringSafe(sql, p);
  }

  private String buildBaljuSourceMemo(String sujuJumunNumber, String projno, String sujuName) {
    StringBuilder sb = new StringBuilder("수주");
    if (sujuJumunNumber != null && !sujuJumunNumber.trim().isEmpty()) {
      sb.append(' ').append(sujuJumunNumber.trim());
    }
    if (projno != null && !projno.trim().isEmpty()) {
      sb.append(" / ").append(projno.trim());
    }
    if (sujuName != null && !sujuName.trim().isEmpty()) {
      sb.append(" / ").append(sujuName.trim());
    }
    return cut(sb.toString(), BALJU_DESC_MAX);
  }

  /** varchar 길이 초과로 저장 전체가 롤백되는 것을 막는다. */
  private static String cut(String v, int max) {
    if (v == null) return null;
    return (v.length() <= max) ? v : v.substring(0, max);
  }

  /* ---------- 헬퍼 ---------- */
  /*private Map<String, Object> resolveOrCreateCompany(String name, String spjangcd,
                                                     User user, Map<String, Company> cache) {
    Map<String, Object> r = new HashMap<>();
    if (name == null || name.trim().isEmpty()) {
      r.put("id", null);
      r.put("code", "");
      r.put("name", "");
      return r;
    }
    String nm = name.trim();

    // 1) 이미 존재(캐시 = 사업장 내 기존 거래처)하면 그대로 사용
    Company company = cache.get(nm);

    // 2) 없으면 신규 거래처 등록 (save_Comp 신규등록과 동일하게 세팅)
    if (company == null) {
      company = new Company();
      company.setCode(sujuService.getNextCompCode());   // 신규 거래처 코드 채번
      company.setName(nm);
      company.setCompanyType(DEFAULT_COMPANY_TYPE);      // ★ 거래처 구분 (save_Comp의 cboCompanyType 대응)
      company.setTelNumber("");
      company.setBusinessNumber("");
      company.setBusinessType("");
      company.setBusinessItem("");
      company.setRelyn("0");
      company.setAddress("");
      company.setFaxNumber("");
      company.setSalesManager("");
      company.setEmail("");
      company.setSpjangcd(spjangcd);
      if (user != null) company.set_audit(user);

      company = companyRepository.save(company);
      cache.put(nm, company);
    }

    r.put("id", company.getId());
    r.put("code", company.getCode());
    r.put("name", company.getName());
    return r;
  }*/

// ===================================================================
//  엑셀 업로드 저장용 헬퍼 (공정 품목 get-or-create)
//  ※ 중분류(user_code) 는 사용하지 않는다.
// ===================================================================

  // 업체명 → 매입처(company."CompanyType" = 'purchase') id 조회, 없으면 신규 등록
  private Integer findPurchaseCompanyIdByName(String name, Map<String, Integer> cache, String spjangcd, User user) {
    if (name == null || name.trim().isEmpty()) return null;
    String nm = name.trim();
    if (cache.containsKey(nm)) return cache.get(nm);

    String sql = "SELECT id FROM company "
                   + "WHERE \"Name\" = :name AND \"CompanyType\" = 'purchase' "
                   + "ORDER BY id LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("name", nm);
    Integer id;
    try {
      id = sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      id = null;
    }

    if (id == null) {
      // 자사(일진)는 내작이라 발주 대상이 아니다. 매입처로 만들지 않는다.
      if (nm.contains(INHOUSE_COMPANY_NAME)) {
        log.info("[excel_save] 자사 업체이므로 매입처 등록 생략: [{}]", nm);
        cache.put(nm, null);
        return null;
      }

      // 매입처 신규 등록
      //  "BusinessNumber" 에 UNIQUE 제약이 있어 빈 문자열을 넣으면 두 번째 등록부터 충돌한다.
      //  값이 없으므로 NULL 로 둔다 (PostgreSQL 은 NULL 중복을 허용).
      Company company = new Company();
      company.setCode(sujuService.getNextCompCode());
      company.setName(nm);
      company.setCompanyType("purchase");
      company.setBusinessNumber(null);
      company.setRelyn("0");
      company.setSpjangcd(spjangcd);
      if (user != null) company.set_audit(user);

      id = companyRepository.save(company).getId();
      log.info("[excel_save] 매입처 신규 등록: [{}] id={}", nm, id);
    }

    cache.put(nm, id);
    return id;
  }

  // 제작구분 판정: 제작업체 우선, 비어 있으면 설계업체로 판단
  //   '일진' 이면 내작(self), 그 외 값이 있으면 외작(outsource), 둘 다 비면 null
  private String resolveMakeType(String makeCompName, String designCompName) {
    String basis = str(makeCompName);
    if (basis.isEmpty()) basis = str(designCompName);
    if (basis.isEmpty()) return null;
    return basis.contains(INHOUSE_COMPANY_NAME) ? "self" : "outsource";
  }

  // 품목 신규 등록 후 id 반환
  //  ※ 기존 품목을 이름으로 재사용하지 않는다. 호출할 때마다 새 품목을 채번한다.
  private Integer createMaterial(String name, String groupCode, String spjangcd, User user,
                                 String sourceMemo) {

    if (name == null || name.trim().isEmpty()) return null;
    String key = name.trim();

    Integer groupId = findMaterialGroupIdByCode(groupCode);
    if (groupId == null) {
      throw new IllegalStateException(
        "품목그룹을 찾을 수 없습니다. mat_grp 에 Code='" + groupCode + "' 인 행이 필요합니다.");
    }

    Material m = new Material();
    m.setCode(sujuService.getNextMatCode());
    m.setName(key);
    m.setMaterialGroupId(groupId);
    m.setFactory_id(1);
    m.setSpjangcd(spjangcd);
    m.setUseyn("0");
    m.setStoreHouseId(3);
    m.setPurchaseOrderStandard("mrp");
    m.setValidDays(1);
    m.setDescription(sourceMemo);   // 프로젝트 / 수주명
    // mat_user_code 는 채우지 않는다 (중분류 미사용)
    m.set_audit(user);

    Material saved = materialRepository.save(m);
    return saved.getId();
  }

  // 품목그룹 code → material_group id
  private Integer findMaterialGroupIdByCode(String code) {
    if (code == null || code.isEmpty()) return null;
    String sql = "SELECT id FROM mat_grp WHERE \"Code\" = :code LIMIT 1";
    MapSqlParameterSource p = new MapSqlParameterSource().addValue("code", code);
    try {
      return sqlRunner.queryForObject(sql, p, (rs, n) -> rs.getInt(1));
    } catch (Exception e) {
      return null;
    }
  }

  // ===================================================================
//  엑셀 → items 파싱 (excel_save / excel_parse 공통)
//  반환: Map { "formName": String, "items": List<Map> }
// ===================================================================
  private Map<String, Object> parseSujuExcel(MultipartFile file) throws IOException {
    try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
      Sheet sheet = wb.getSheetAt(0);
      List<CellRangeAddress> merged = sheet.getMergedRegions();

      Row headerRow = sheet.getRow(1);
      if (headerRow == null) {
        throw new IllegalArgumentException("헤더(2행)를 찾을 수 없습니다.");
      }

      // A1 제목에서 프로젝트명 추출 ("NQ6 제작출도리스트" → "NQ6")
      Row titleRow = sheet.getRow(0);
      String titleRaw = (titleRow == null) ? "" : getString(titleRow.getCell(0));
      String projectTitle = extractProjectTitle(titleRaw);

      Map<String, Integer> col = new HashMap<>();
      for (Cell c : headerRow) {
        String h = getString(c).replaceAll("\\s+", "");
        if (!h.isEmpty()) col.put(h, c.getColumnIndex());
      }

      boolean isMakeList = col.containsKey("도면출도일") || col.containsKey("PINSHIFTUNIT수량")
                             || col.containsKey("UNIT수량");
      boolean isQtyList  = col.containsKey("유니트갯수") || col.containsKey("다리발개수")
                             || col.containsKey("설비타입");
      String formName;
      if (isMakeList) formName = "제작출도리스트";
      else if (isQtyList) formName = "공정별 수량집계";
      else throw new IllegalArgumentException("인식할 수 없는 엑셀 양식입니다. (헤더 2행 확인)");

      List<Map<String, Object>> items = new ArrayList<>();
      int last = sheet.getLastRowNum();
      for (int r = 2; r <= last; r++) {
        Row row = sheet.getRow(r);
        if (row == null) continue;

        String lineRaw  = cellByHeader(sheet, merged, row, col, "LINE");
        String procName = cellByHeader(sheet, merged, row, col, "공정명");
        if (procName.isEmpty()) continue;
        if (lineRaw.contains("합계") || procName.contains("합계")) continue;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", lineRaw);
        m.put("procName", procName);
        m.put("jigSet", cellByHeader(sheet, merged, row, col, "JIGSET"));

        if (isMakeList) {
          m.put("equipType",    cellByHeader(sheet, merged, row, col, "장비"));
          m.put("unitQty",      cellByHeader(sheet, merged, row, col, "UNIT수량"));
          m.put("pinShiftUnit", cellByHeader(sheet, merged, row, col, "PINSHIFTUNIT수량"));
          m.put("drawDate", normalizeDrawDate(cellByHeader(sheet, merged, row, col, "도면출도일")));
          m.put("itemRemark", cellByHeader(sheet, merged, row, col, "비고"));
          m.put("designCompName", cellByHeader(sheet, merged, row, col, "설계업체"));
          m.put("makeCompName",   cellByHeader(sheet, merged, row, col, "제작업체"));
          m.put("legSpec", "");
          m.put("legCnt", "");
        } else {
          m.put("unitQty",   cellByHeader(sheet, merged, row, col, "유니트갯수"));
          m.put("equipType", cellByHeader(sheet, merged, row, col, "설비타입"));
          m.put("legSpec",   cellByHeader(sheet, merged, row, col, "다리발사양"));
          m.put("legCnt",    cellByHeader(sheet, merged, row, col, "다리발개수"));
          m.put("pinShiftUnit", "");
          m.put("drawDate", "");
          m.put("itemRemark", "");
          m.put("designCompName", "");
          m.put("makeCompName", "");
        }
        items.add(m);
      }

      Map<String, Object> out = new HashMap<>();
      out.put("formName", formName);
      out.put("projectTitle", projectTitle);
      out.put("items", items);
      return out;
    }
  }

  /* ---------- 헤더명으로 셀 값 (병합 셀이면 좌상단 값 상속) ---------- */
  private String cellByHeader(Sheet sheet, List<CellRangeAddress> merged,
                              Row row, Map<String, Integer> col, String headerNoSpace) {
    Integer idx = col.get(headerNoSpace);
    if (idx == null) return "";
    return getMergedString(sheet, merged, row.getRowNum(), idx);
  }

  /* ---------- 병합 영역을 고려해 실제 값을 가진 셀을 찾아 반환 ---------- */
  private String getMergedString(Sheet sheet, List<CellRangeAddress> merged, int rowIdx, int colIdx) {
    Cell cell = null;
    Row row = sheet.getRow(rowIdx);
    if (row != null) cell = row.getCell(colIdx);

    String v = getString(cell);
    if (!v.isEmpty()) return v;

    // 비어 있으면 이 좌표를 포함하는 병합영역의 좌상단 셀 값을 가져옴
    for (CellRangeAddress ca : merged) {
      if (ca.isInRange(rowIdx, colIdx)) {
        Row fr = sheet.getRow(ca.getFirstRow());
        if (fr != null) {
          return getString(fr.getCell(ca.getFirstColumn()));
        }
      }
    }
    return "";
  }

  private String getString(Cell c) {
    if (c == null) return "";
    switch (c.getCellType()) {
      case STRING:  return c.getStringCellValue().trim();
      case NUMERIC:
        if (DateUtil.isCellDateFormatted(c)) {
          return new java.text.SimpleDateFormat("yyyy-MM-dd").format(c.getDateCellValue());
        }
        double d = c.getNumericCellValue();
        return (d == Math.floor(d)) ? String.valueOf((long) d) : String.valueOf(d);
      case BOOLEAN: return String.valueOf(c.getBooleanCellValue());
      case FORMULA:
        try { return c.getStringCellValue().trim(); }
        catch (Exception e) { return String.valueOf(c.getNumericCellValue()); }
      default: return "";
    }
  }

  /* ---------- A1 제목 → 프로젝트명 ----------
     "NQ6 제작출도리스트"                      → "NQ6"
     "LW 새한산업 공정별 타입 및 다리발 수량집계" → "LW 새한산업"
     "제작출도리스트" (프로젝트명 없음)          → null
     양식명이 시작되는 지점에서 잘라낸다. 표식이 없으면 제목 전체를 쓴다. */
  private static final String[] TITLE_FORM_MARKERS = { "제작출도리스트", "공정별", "수량집계" };

  private String extractProjectTitle(String raw) {
    if (raw == null) return null;
    String s = raw.trim().replaceAll("\\s+", " ");
    if (s.isEmpty()) return null;

    int cut = -1;
    for (String marker : TITLE_FORM_MARKERS) {
      int i = s.indexOf(marker);
      if (i >= 0 && (cut < 0 || i < cut)) cut = i;
    }
    if (cut == 0) return null;              // 제목이 양식명뿐이면 프로젝트명 없음
    String title = (cut > 0) ? s.substring(0, cut).trim() : s;
    return title.isEmpty() ? null : title;
  }

  /* ---------- "06월 17일", "6/17" 등 → yyyy-MM-dd ---------- */
  private String normalizeDrawDate(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    if (s.isEmpty()) return "";
    if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return s;

    java.util.regex.Matcher m = java.util.regex.Pattern
                                  .compile("(\\d{1,2})\\D+(\\d{1,2})").matcher(s);
    if (m.find()) {
      int mon = Integer.parseInt(m.group(1));
      int day = Integer.parseInt(m.group(2));
      if (mon >= 1 && mon <= 12 && day >= 1 && day <= 31) {
        int year = java.time.LocalDate.now().getYear();
        return String.format("%04d-%02d-%02d", year, mon, day);
      }
    }
    return "";
  }

}