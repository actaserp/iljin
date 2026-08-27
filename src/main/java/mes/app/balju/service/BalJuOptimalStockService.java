package mes.app.balju.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BalJuOptimalStockService {

  @Autowired
  SqlRunner sqlRunner;

  /**
   * 품목구분 미선택(전체)일 때의 표시 범위 — 원재료 / 부재료
   *
   * ※ 이 화면은 발주 대상 품목만 다룬다. 제품(product) / 반제품(semi) 은 제외.
   *   부재료(sub_mat) 에는 구매품(BUY) 과 표준품(STD) 이 함께 속한다.
   *   둘 다 발주 대상이므로 그룹 코드로 더 좁히지 않는다.
   */
  private static final List<String> DEFAULT_MAT_TYPES = List.of("raw_mat", "sub_mat");

  public List<Map<String, Object>> getList(String matName, String status, String matType,
                                           Timestamp start, Timestamp end, String spjangcd) {
    MapSqlParameterSource paramMap = new MapSqlParameterSource();
    paramMap.addValue("matName", matName);
    paramMap.addValue("status", status);
    paramMap.addValue("start", start);
    paramMap.addValue("end", end);
    paramMap.addValue("spjangcd", spjangcd);
    paramMap.addValue("defaultMatTypes", DEFAULT_MAT_TYPES);

    // 빈 문자열은 NULL 로 정규화 (SQL 에서 CAST(:matType AS varchar) IS NULL 로 판정)
    paramMap.addValue("matType",
      (matType == null || matType.isBlank()) ? null : matType.trim());

    String sql = """
      WITH
        -- 0) 자재 마스터(적정재고/현재고/단위/품목구분/품목그룹)
        --    품목구분은 mat_grp."MaterialType" 이 원천이다.
        --    품목그룹이 없는 자재는 구분을 판정할 수 없으므로 제외(INNER JOIN).
        mat AS (
          SELECT
            m.id,
            m."Code"  AS material_code,
            m."Name"  AS material_name,
            COALESCE(u."Name",'') AS unit_name,
            NULLIF(m."Standard1",'') AS mat_standard,
            mg.id     AS mat_grp_id,
            COALESCE(mg."Name",'') AS mat_grp_name,
            COALESCE(mg."Code",'') AS mat_grp_code,
            COALESCE(mg."MaterialType",'') AS mat_type,
            CASE mg."MaterialType"
              WHEN 'product' THEN '제품'
              WHEN 'semi'    THEN '반제품'
              WHEN 'raw_mat' THEN '원재료'
              WHEN 'sub_mat' THEN '부재료'
              ELSE COALESCE(mg."MaterialType",'')
            END AS mat_type_name,
            m.spjangcd,
            COALESCE(m."CurrentStock",0)::numeric AS current_stock,
            COALESCE(
              NULLIF(regexp_replace(m."Avrqty", '[^0-9.-]', '', 'g'), ''),
              '0'
            )::numeric AS optimal_stock
          FROM material m
          LEFT JOIN unit u ON u.id  = m."Unit_id"
          JOIN mat_grp mg  ON mg.id = m."MaterialGroup_id"
          WHERE m.spjangcd = :spjangcd
            AND m."Useyn" = '0'
            AND (
                  (CAST(:matType AS varchar) IS NULL
                     AND mg."MaterialType" IN (:defaultMatTypes))
                  OR mg."MaterialType" = CAST(:matType AS varchar)
                )
        ),
        -- 1) 수주 집계(납품예정일 기준: head.DeliveryDate 우선, 없으면 line.DueDate)
        orders AS (
          SELECT
            s."Material_id" AS material_id,
            s."Standard"    AS standard,
            SUM(
              CASE WHEN COALESCE(s."SujuQty2",0) > 0 THEN s."SujuQty2"
                   ELSE COALESCE(s."SujuQty",0) END
            )::numeric AS order_qty
          FROM suju_head h
          JOIN suju s ON s."SujuHead_id" = h.id
          WHERE h.spjangcd = :spjangcd
            AND s."Material_id" IS NOT NULL
            AND COALESCE(h."DeliveryDate", s."DueDate")
                BETWEEN CAST(:start AS date) AND CAST(:end AS date)
          GROUP BY s."Material_id", s."Standard"
        ),
        -- 2) 표시 대상
        --    ※ 자재 기준 LEFT JOIN. 수주가 없는 품목도 수주량 0 으로 표시된다.
        --      (이 경우 필요수량 = 적정재고 - 현재고)
        --      수주가 있는 품목만 보려면 아래를 JOIN 으로 바꿀 것.
        base AS (
          SELECT
            m.id AS material_id,
            m.material_code,
            m.material_name,
            m.mat_grp_id,
            m.mat_grp_name,
            m.mat_grp_code,
            m.mat_type,
            m.mat_type_name,
            COALESCE(o.standard, m.mat_standard) AS standard,
            m.unit_name,
            COALESCE(o.order_qty,0) AS order_qty,
            0::numeric              AS incoming_qty,   -- ← 계산 제외(표시만 0)
            m.current_stock,
            m.optimal_stock
          FROM mat m
          LEFT JOIN orders o ON o.material_id = m.id
        )
        SELECT *
        FROM (
          SELECT
            material_id,
            material_code,
            material_name,
            mat_grp_id,
            mat_grp_name,
            mat_grp_code,
            mat_type,
            mat_type_name,
            standard,
            unit_name,
            order_qty,
            current_stock,
            optimal_stock,
            /* 필요수량 = (수주 + 적정재고) - 현재고 의 양수부 */
            GREATEST(
              (COALESCE(order_qty,0) + COALESCE(optimal_stock,0)) - COALESCE(current_stock,0),
              0
            )::numeric AS need_more_qty,
            CASE
              WHEN COALESCE(current_stock,0) - (COALESCE(order_qty,0) + COALESCE(optimal_stock,0)) < 0 THEN '부족'
              WHEN COALESCE(current_stock,0) - (COALESCE(order_qty,0) + COALESCE(optimal_stock,0)) = 0 THEN '적정'
              ELSE '여유'
            END AS state
          FROM base
        ) t
        WHERE 1=1 
      """;

    // 품명(키워드) 필터: 이름/코드 모두 검색
    if (matName != null && !matName.isEmpty()) {
      sql += " AND (t.material_name ILIKE :matName OR t.material_code ILIKE :matName) ";
      paramMap.addValue("matName", "%" + matName + "%");
    }

    // 상태 필터
    if (status != null && !status.isBlank() && !"전체".equals(status.trim())) {
      String st = status.trim();
      switch (st.toLowerCase()) {
        case "shortage":
        case "lack":
        case "insufficient": st = "부족"; break;
        case "proper":
        case "ok":
        case "equal":       st = "적정"; break;
        case "excess":
        case "surplus":     st = "여유"; break;
        default: break;
      }
      sql += " AND t.state = :status ";
      paramMap.addValue("status", st);
    }

    // 부족 → 적정 → 여유 순.
    // 같은 상태 안에서는 필요수량이 큰 것 → 수주가 있는 것 순으로 올린다.
    sql += """
        ORDER BY CASE t.state
                   WHEN '부족' THEN 0
                   WHEN '적정' THEN 1
                   ELSE 2
                 END
               , t.need_more_qty DESC
               , (t.order_qty > 0) DESC
               , t.mat_grp_id
               , t.material_code
               , COALESCE(t.standard,'')
        """;

//    log.info("paramMap:{}", paramMap);
//    log.info("적정재고 현황(납품예정일 기준) sql:{}", sql);

    return sqlRunner.getRows(sql, paramMap);
  }

}