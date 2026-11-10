package mes.app.production;

import lombok.RequiredArgsConstructor;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;

/**
 * 지그 라벨 (100×50mm) 출력용 조회.
 *
 * <p>[LOT 은 여기서 만들지 않는다]
 *   내작 지그 LOT 은 <b>검사 합격 등록</b> 때 ProdInspectService.produceIn 이 만든다
 *   (mat_lot + mat_inout produced_in, 제품창고). 예전 PC 화면의 공정 조립 LOT 도 같은 경로로 찾는다.
 *   이 화면은 그 LOT 번호를 라벨로 찍기만 한다 — 기존 PDA 출하(LOT 스캔 → 출고) 흐름이 그대로 동작한다.
 *   외작 지그는 여기서 다루지 않는다. 수불전표등록(외작 입고 → 로트 입고등록)에서 LOT 을 만들고 출력한다.
 *
 * <p>[언제 찍나]  검사 공정에서 합격(또는 특채) 등록 직후. 프린터가 3차원 측정실에 있다.
 *   불합격 지그는 LOT 이 없으므로 라벨도 나오지 않는다.
 *
 * <p>[도면]  dwg_base = LOT 이 생길 때까지 올라온 품목 도면의 최종 등록일. 라벨에 찍는다.
 *   출하 스캔(ShipmentApiService)이 그 뒤 리비전이 오른 도면을 찾아 막는다.
 *
 * <p>[수량]  지그 1대 = LOT 1건 (검사 합격·외작 입고 모두). 화면은 LOT 마다 라벨 1장을 찍는다.
 *   같은 LOT 라벨을 여러 장 찍지 않는다 — PDA 에서 수량이 겹쳐 잡힌다.
 */
@RestController
@RequestMapping("/api/production/prod_label")
@RequiredArgsConstructor
public class ProdLabelController {

	private final SqlRunner sqlRunner;

	/** 품목(suju 1행)의 지그 LOT 목록. 출고가 끝난 LOT 은 기본으로 뺀다 */
	@GetMapping("/jig_lots")
	public AjaxResult jigLots(@RequestParam("spjangcd") String spjangcd,
							  @RequestParam("sujuId") Integer sujuId,
							  @RequestParam(value = "includeShipped", defaultValue = "false") boolean includeShipped,
							  // 방금 등록한 검사 건의 LOT 만 (합격 직후 출력). 없으면 품목 전체 = 재출력
							  @RequestParam(value = "matProduceId", required = false) Integer matProduceId) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		p.addValue("sujuId", sujuId);
		p.addValue("mpId", matProduceId);

		String sql = """
            SELECT ml.id                               AS lot_id
                 , ml."LotNumber"                      AS lot_number
                 , COALESCE(ml."InputQty", 0)          AS input_qty
                 , COALESCE(ml."CurrentStock", 0)      AS current_stock
                 , TO_CHAR(ml."InputDateTime", 'YYYY-MM-DD') AS input_date
                 , s.id                                AS suju_id
                 , s.project_id                        AS proj_no
                 , d.projnm                            AS proj_name
                 , d.balcltnm                          AS order_company
                 , s.line                              AS line_name
                 , s."Material_Name"                   AS item_name
                 , s.equip_type                        AS equip_type
                 , s.make_type                         AS make_type
                 -- 도면 기준: 이 LOT(검사 합격) 시점까지 올라온 품목 도면의 최종 등록일
                 , (SELECT TO_CHAR(MAX(a._created), 'YYYY-MM-DD')
                      FROM attach_file a
                     WHERE a."TableName" = 'suju_drawing' AND a."DataPk" = s.id
                       AND a."AttachName" = 'drawing'
                       AND a._created <= COALESCE(ml."InputDateTime", now()))   AS dwg_base
            FROM mat_lot ml
            JOIN mat_produce mp ON ml."SourceTableName" = 'mat_produce'
                               AND ml."SourceDataPk" = mp.id
            JOIN job_res j      ON j.id = mp."JobResponse_id"
                               AND j."SourceTableName" = 'suju'
            JOIN suju s         ON s.id = j."SourceDataPk"
            LEFT JOIN tb_da003 d ON d.projno = s.project_id AND d.spjangcd = s.spjangcd
            WHERE s.id = :sujuId
              AND s.spjangcd = :spjangcd
              AND (CAST(:mpId AS integer) IS NULL OR mp.id = CAST(:mpId AS integer))
            """ + (includeShipped ? "" : "  AND COALESCE(ml.\"CurrentStock\", 0) > 0\n") + """
            ORDER BY ml.id
            """;

		AjaxResult result = new AjaxResult();
		result.success = true;
		result.data = sqlRunner.getRows(sql, p);
		return result;
	}
}