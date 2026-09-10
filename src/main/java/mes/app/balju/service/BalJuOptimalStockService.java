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

  /**
   * 소요량 집계에서 제외할 부품 구분.
   * 제작품은 Material_id 가 NULL 이라 자연히 빠지지만, 데이터 오염 대비 이중 방어.
   */
  private static final String EXCLUDE_GUBUN = "제작품";

  public List<Map<String, Object>> getList(String matName, String status, String matType,
                                           Timestamp start, Timestamp end, String spjangcd) {
    MapSqlParameterSource paramMap = new MapSqlParameterSource();
    paramMap.addValue("matName", matName);
    paramMap.addValue("status", status);
    paramMap.addValue("start", start);
    paramMap.addValue("end", end);
    paramMap.addValue("spjangcd", spjangcd);
    paramMap.addValue("defaultMatTypes", DEFAULT_MAT_TYPES);
    paramMap.addValue("excludeGubun", EXCLUDE_GUBUN);

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
        -- 1) 소요량 집계 (납품예정일 기준: head.DeliveryDate 우선, 없으면 line.DueDate)
        --
        --    ※ 발주 대상의 실제 수요는 생산지시 BOM(iljin_suju_bom) 의 구매품/STD 행이다.
        --      suju."Material_id" 는 지그 세트(S10, RS01) 자체의 품목마스터를 가리키므로
        --      이 화면의 표시 범위(raw_mat/sub_mat)와 거의 매칭되지 않는다.
        --      기존 동작 유지를 위해 (b) 로 함께 UNION 한다.
        demand AS (
          SELECT material_id, SUM(qty)::numeric AS order_qty
          FROM (
            -- (a) 생산지시 BOM 의 구매품 / STD
            --
            --     ※ "Qty" 는 그 품목(suju 1행)에 실제로 들어가는 총 개수다.
            --       유니트수를 곱하지 않는다. 현장 입력이 총량 기준이므로
            --       QtyType 과 무관하게 입력값을 그대로 소요량으로 본다.
            --       (A19 / AIR CYL 2,558 = 그 품목에 들어가는 전체 수량)
            SELECT
              b."Material_id" AS material_id,
              COALESCE(b."Qty",0) AS qty
            FROM iljin_suju_bom b
            JOIN suju      s ON s.id = b."Suju_id"
            JOIN suju_head h ON h.id = s."SujuHead_id"
            WHERE h.spjangcd = :spjangcd
              AND b."Material_id" IS NOT NULL
              AND b."Gubun" <> CAST(:excludeGubun AS varchar)
              -- ※ State='완료' 제외 여부는 보류.
              --   '완료' 가 입고 완료를 뜻하고 material."CurrentStock" 이 실제로
              --   갱신되는 것이 확인되기 전에는 빼면 안 된다.
              --   (수요만 빠지고 재고는 안 올라 발주를 놓친다)
              -- AND b."State" <> '완료'
              AND COALESCE(h."DeliveryDate", s."DueDate")
                  BETWEEN CAST(:start AS date) AND CAST(:end AS date)

            UNION ALL

            -- (b) 수주가 자재를 직접 가리키는 경우 (기존 동작)
            SELECT
              s."Material_id",
              CASE WHEN COALESCE(s."SujuQty2",0) > 0 THEN s."SujuQty2"
                   ELSE COALESCE(s."SujuQty",0) END
            FROM suju_head h
            JOIN suju s ON s."SujuHead_id" = h.id
            WHERE h.spjangcd = :spjangcd
              AND s."Material_id" IS NOT NULL
              AND COALESCE(h."DeliveryDate", s."DueDate")
                  BETWEEN CAST(:start AS date) AND CAST(:end AS date)
          ) d
          GROUP BY material_id
        ),
        -- 2) 표시 대상
        --    ※ 자재 기준 LEFT JOIN. 소요가 없는 품목도 소요량 0 으로 표시된다.
        --      (이 경우 발주검토수량 = 적정재고 - 현재고)
        --      소요가 있는 품목만 보려면 아래를 JOIN 으로 바꿀 것.
        --
        --    ※ standard 는 자재 마스터 값으로 고정한다.
        --      이전 버전은 수주의 "Standard" 를 COALESCE 로 끌어썼는데,
        --      집계는 (material_id, standard) 로 하고 JOIN 은 material_id 로만 해서
        --      한 자재가 여러 규격으로 수주되면 행이 복제되는 문제가 있었다.
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
            m.mat_standard AS standard,
            m.unit_name,
            COALESCE(o.order_qty,0) AS order_qty,
            -- 발주잔량(미입고). 발주 테이블 연계 전까지 표시만 0.
            -- 이 값이 0 인 동안에는 이미 발주한 건이 계산에 반영되지 않으므로
            -- 화면 헤더를 '발주검토수량' 으로 두고 자동 판정처럼 보이지 않게 할 것.
            0::numeric              AS incoming_qty,
            m.current_stock,
            m.optimal_stock
          FROM mat m
          LEFT JOIN demand o ON o.material_id = m.id
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
            incoming_qty,
            current_stock,
            optimal_stock,
            /* 발주검토수량 = (소요량 + 적정재고) - 현재고 의 양수부 */
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
    // 같은 상태 안에서는 발주검토수량이 큰 것 → 소요가 있는 것 순으로 올린다.
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