package mes.app.production.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import mes.app.definition.service.EquipmentService;
import mes.app.inventory.service.LotService;
import mes.domain.entity.*;
import mes.domain.model.AjaxResult;
import mes.domain.repository.*;
import mes.domain.services.DateUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class ProductionResultService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	StorehouseRepository storehouseRepository;

	@Autowired
	MatLotConsRepository matLotConsRepository;

	@Autowired
	MatLotRepository matLotRepository;

	@Autowired
	private LotService lotService;

	@Autowired
	MatConsuRepository matConsuRepository;

	@Autowired
	JobResRepository jobResRepository;

	@Autowired
	MatProcInputReqRepository matProcInputReqRepository;

	@Autowired
	JobResDefectRepository jobResDefectRepository;

	@Autowired
	MatProduceRepository matProduceRepository;

	@Autowired
	MaterialRepository materialRepository;

	@Autowired
	WorkcenterRepository workcenterRepository;

	@Autowired
	SystemOptionRepository systemOptionRepository;

	@Autowired
	MatProcInputRepository matProcInputRepository;

	@Autowired
	MaterialGroupRepository materialGroupRepository;

	@Autowired
	MatInoutRepository matInoutRepository;

	@Autowired
	SujuRepository sujuRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	TestResultRepository testResultRepository;

	@Autowired
	TestItemResultRepository testItemResultRepository;

	@Autowired
	EquipmentService equipmentService;

	@Autowired
	EquRunRepository equRunRepository;

	public void add_jobres_defectqty_inout(Integer jrPk, int id) {

		List<StoreHouse> sh = this.storehouseRepository.findByHouseType("defect");
		Integer defectHousePk = null;
		if (sh.size() > 0) {
			defectHousePk = sh.get(0).getId();
		} else {
			return;
		}

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);
		dicParam.addValue("housePk", defectHousePk);
		dicParam.addValue("userId", id);

		String sql = """
				 insert into mat_inout ("Material_id","StoreHouse_id", "InoutDate", "InoutTime", "InOut", "InputType"
               , "InputQty", "Description", "SourceDataPk", "SourceTableName", "State", _status, _created, _creater_id)
               select jr."Material_id"
               , :housePk
               , now()::date as "InoutDate"
               , now()::time as "InoutTime"
               ,'in' as "InOut"
               ,'produced_in' as "InputType"
               , jrd."DefectQty" as "InputQty"
               , dt."Name" as "Description"
               , jrd.id as "SourceDataPk"
               , 'job_res_defect' as "SourceTableName"
               , 'confirmed' as status
               , 'a' as _status
               , now() as _created
               , :userId as _creater_id
               from job_res_defect jrd 
               inner join job_res jr on jr.id=jrd."JobResponse_id"
               left join defect_type dt on dt.id = jrd."DefectType_id" 
               where jrd."DefectQty" > 0 
               and jrd."JobResponse_id" = :jrPk
				""";

		this.sqlRunner.execute(sql, dicParam);
	}

	public void delete_jobres_defectqty_inout(Integer jrPk) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);

		String sql = """
				delete from mat_inout 
		        where "SourceTableName"='job_res_defect' 
		        and "SourceDataPk" in (select id 
	            from job_res_defect 
	            where "JobResponse_id" = :jrPk)
				""";
		this.sqlRunner.execute(sql, dicParam);

	}

	public List<Map<String, Object>> get_chasu_bom_mat_qty_list(int id) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);

		String sql = """
	       		with mp as(
		        select 
		        "Material_id"
		        , (COALESCE("GoodQty",0)+COALESCE("DefectQty",0)+COALESCE("ScrapQty",0)+COALESCE("LossQty",0)) as prod_qty
		        , "ProductionDate"
		        from mat_produce
		         where id = :id
		        ), bom1 as (
		        select b1.id as bom_pk, b1."Material_id" as prod_pk
		        , b1."OutputAmount" as produced_qty
		        , mp.prod_qty
		        , row_number() over(partition by b1."Material_id" order by b1."Version" desc) as g_idx
		        from bom b1
		         inner join mp on mp."Material_id"=b1."Material_id"
		        where b1."BOMType" = 'manufacturing' and mp."ProductionDate" between b1."StartDate" and b1."EndDate"  
		        ), BT as (
		        select 
		        bc."Material_id" as mat_pk
		        , bom1.produced_qty
		        , bc."Amount" as quantity 
		        , bc."Amount" / bom1.produced_qty as bom_ratio
		        , bc."Amount" / bom1.produced_qty * bom1.prod_qty as chasu_bom_qty 
		        from bom_comp bc 
		        inner join bom1 on bom1.bom_pk=bc."BOM_id"
		        where bom1.g_idx = 1
		        )
		        select 
		        BT.mat_pk
		        , mg."MaterialType" as mat_type
		        , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
		        , mg."Name" as mat_group_name
		        , m."Code" as mat_code
		        , m."Name" as mat_name
		        , u."Name" as unit_name
		        , BT.bom_ratio
		        , BT.chasu_bom_qty
		        , coalesce(m."LotUseYN",'N') as "lotUseYn"
		        from BT
		        inner join material m on m.id=BT.mat_pk
		        left join mat_grp mg on mg.id=m."MaterialGroup_id"
		        left join unit u on u.id=m."Unit_id"
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public void calculate_balance_mat_lot_with_job_res(int id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);

		String sql = """
                      with ll as(
                      select 
                      ml.id as ml_id
                      from job_res jr  
                      inner join mat_proc_input mpi on mpi."MaterialProcessInputRequest_id"=jr."MaterialProcessInputRequest_id"
                      inner join mat_lot ml on ml.id = mpi."MaterialLot_id" 
                      where jr.id = :id
                      ), ss as( select 
                      ll.ml_id, sum(mlc."OutputQty") as out_sum 
                      from ll 
                      left join mat_lot_cons mlc on ll.ml_id= mlc."MaterialLot_id" 
                      group by ll.ml_id
                      ), T as(
                      select 
                      ss.ml_id, coalesce(ss.out_sum,0) as out_sum, ml."InputQty" 
                      from ss
                      inner join mat_lot ml on ml.id=ss.ml_id
                      )
                      update mat_lot set "OutQtySum" = T.out_sum
                      , "CurrentStock" = mat_lot."InputQty"-T.out_sum
                      from T 
                      where T.ml_id = mat_lot.id
                """;

		this.sqlRunner.execute(sql, dicParam);
	}

	public void delete_mlc_and_rebalance_ml(int id) {
		List<MatLotCons> mcList = this.matLotConsRepository.findBySourceTableNameAndSourceDataPk("mat_produce", id);

		for (int i = 0; i < mcList.size(); i++) {
			MaterialLot ml = this.matLotRepository.getMatLotById(mcList.get(i).getMaterialLotId());
			Integer mId = ml.getId();
			this.matLotConsRepository.deleteById(mcList.get(i).getId());

			MapSqlParameterSource dicParam = new MapSqlParameterSource();
			dicParam.addValue("mId", mId);

			String sql = """
                             with SS as (
                             select 
                             ml.id as ml_id, sum("OutputQty") as out_qty_sum
                             from mat_lot_cons mlc 
                             inner join mat_lot ml on ml.id = mlc."MaterialLot_id"   
                             where ml.id= :mId
                             group by ml.id
                             )        
                             update mat_lot set 
                              "CurrentStock" = mat_lot."InputQty"-COALESCE(ss.out_qty_sum,0)
                              , "OutQtySum" = COALESCE(ss.out_qty_sum,0)
                              , _modified = now()
                             from ss
                             where ss.ml_id = mat_lot.id
                    """;


			this.sqlRunner.execute(sql, dicParam);
		}
	}

	public void calculate_balance_mat_lot_with_mat_prod(int id) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("mpId", id);

		String sql = """
                   with MS as (
                 	    select 
                      ml.id, sum(mlc."OutputQty") as "OutQtySum"
                      from mat_lot_cons mlc 
                      inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                      inner join mat_produce mp on mp.id= mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
                      where mlc."SourceDataPk"= :mpId
                      group by ml.id 
                      )
                      update mat_lot set 
                      "CurrentStock" = mat_lot."InputQty"-COALESCE(MS."OutQtySum",0)
                      , "OutQtySum" = MS."OutQtySum"
                      , _modified = now()
                      from MS
                      where MS.id = mat_lot.id
                """;

		this.sqlRunner.execute(sql, dicParam);
	}

	public List<Map<String, Object>> getProdResult(String dateFrom, String dateTo, String isIncludeComp, String spjangcd, String choMat, Integer cboFactory, String company) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("dateFrom", dateFrom);
		dicParam.addValue("dateTo", dateTo);
		dicParam.addValue("isIncludeComp", isIncludeComp);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("cboFactory", cboFactory);
		String pattern = (choMat == null || choMat.isBlank()) ? "%" : "%" + choMat + "%";
		dicParam.addValue("choMat", pattern);

		String sql = """
			WITH T AS (
			  SELECT
				  jr.id                              AS child_id,
				  jr."Parent_id"                     AS parent_id,
				  jr."Description" as memo ,
				  COALESCE(jr."Parent_id", jr.id)    AS base_id,
				  CASE WHEN jr."State"='working' THEN 1 ELSE 0 END AS is_working,
				  CASE WHEN jr."State"='stopped' THEN 1 ELSE 0 END AS is_stopped
			  FROM job_res jr
			  WHERE jr."ProductionDate" BETWEEN CAST(:dateFrom AS date) AND CAST(:dateTo AS date)
				AND jr.spjangcd = :spjangcd
			),
			S AS (
			  SELECT
				  T.*,
				  -- 대표행 선택: working 우선, 다음 최근 id
				  ROW_NUMBER() OVER (
						PARTITION BY T.base_id
						ORDER BY
						  T.is_working DESC,                                  -- 1) working 우선
						  CASE WHEN T.parent_id IS NULL THEN 1 ELSE 0 END DESC, -- 2) 그다음 부모 우선
						  T.child_id DESC                                     -- 3) 마지막 타이브레이커: 최신 id
					  ) AS rn,
				  -- 체인에 working 있는지 (있으면 1)
				  MAX(T.is_working) OVER (PARTITION BY T.base_id) AS any_working
				  , MAX(T.is_stopped) OVER (PARTITION BY T.base_id) AS any_stopped
			  FROM T
			),
			F AS (
			  SELECT
				 S.child_id                                  AS id                         -- 대표행 id
			   , C."WorkOrderNumber"                         AS order_num
			   , TO_CHAR(B."ProductionDate",'yyyy-mm-dd')    AS prod_date                  -- 기본정보는 base(부모)
			   , TO_CHAR(su."DueDate",'yyyy-mm-dd')    AS due_date
			   , C."LotNumber"                               AS lot_num
			   , TO_CHAR(B."StartTime",'hh24:mi')            AS start_time
			   , TO_CHAR(B."EndTime",'hh24:mi')              AS end_time
			   , WC.id                                       AS workcenter_id
			   , WC."Name"                                   AS workcenter
			   , C."ShiftCode"                                AS shift_code
			   , SH."Name"                                    AS shift_name
			   , B."WorkIndex"                                AS work_idx
			
			   -- 파생 상태: working 있으면 working, 아니면 부모 상태
			   , CASE
					 WHEN S.any_working = 1 THEN 'working'
					 WHEN S.any_stopped = 1 THEN 'stopped'
					 ELSE B."State"
				 END AS state
				 , fn_code_name('job_state',
					 CASE
						 WHEN S.any_working = 1 THEN 'working'
						 WHEN S.any_stopped = 1 THEN 'stopped'
						 ELSE B."State"
					 END
				 ) AS job_state
			
			   , C."WorkerCount"                              AS worker_count
			   , M.id                                         AS mat_pk
			   , M."Code"                                     AS mat_code
			   , M."Name"                                     AS mat_name
			   , fn_code_name('mat_type', MG."MaterialType")  AS mat_type
			   , M."LotSize"                                  AS lot_size
			   , M."Weight"                                   AS weight
			   , U."Name"                                     AS unit
			   , E.id                                         AS equipment_id
			   , E."Name"                                     AS equipment
			   , C."Description"                              AS description
			   , ROUND(B."OrderQty"::numeric, 2)              AS order_qty
			   , ROUND(B."GoodQty"::numeric, 2)              AS good_qty
			   , ROUND(B."DefectQty"::numeric, 2)             AS defect_qty
			   , B."LossQty"                                  AS loss_qty
			   , B."ScrapQty"                                 AS scrap_qty
			   , TO_CHAR(B."ProductionDate" + M."ValidDays", 'yyyy-mm-dd') AS "ValidDays"
			   , M."Routing_id"                               AS routing_id
			   , COALESCE(su."Standard", M."Standard1") as standard
			   , su."CompanyName" as company_name
			   , M."Factory_id" AS "Factory_id"
			   , fa."Name" as fac_name
			   , S.memo
			   , su.id as suju_id
			  FROM S
			  JOIN job_res       C  ON C.id = S.child_id              -- child = 대표행
			  JOIN job_res       B  ON B.id = S.base_id               -- base = 부모
			  LEFT JOIN work_center WC ON WC.id = C."WorkCenter_id"
			  LEFT JOIN equ           E  ON E.id  = C."Equipment_id"
			  LEFT JOIN shift         SH ON SH."Code" = C."ShiftCode"
			  LEFT JOIN material      M  ON M.id = B."Material_id"
			  LEFT JOIN routing       R  ON M."Routing_id" = R.id
			  LEFT JOIN mat_grp       MG ON MG.id = M."MaterialGroup_id"
			  LEFT JOIN unit          U  ON U.id = M."Unit_id"
			  left join suju su on su.id = B."SourceDataPk" and B."SourceTableName" = 'suju'
			  left join factory fa on M."Factory_id" = fa.id
			  WHERE S.rn = 1
			)
			SELECT *
			FROM F
			where  1=1
			and F.mat_name like :choMat
			""";

		if ("false".equalsIgnoreCase(isIncludeComp)) {
			// ★ 파생 상태(state) 기준으로 완료 제외
			sql += " and F.state != 'finished' ";
		}

		if (cboFactory != null) {
			sql += " and F.\"Factory_id\" = :cboFactory ";
		}

		if (StringUtils.isEmpty(company) == false) {
			sql += " AND F.company_name LIKE :company ";
			dicParam.addValue("company", "%" + company + "%");
		}

		sql += " ORDER BY F.prod_date desc, F.order_num, F.id ";

//		log.info("생산실적 입력 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", dicParam.getValues());
		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public Map<String, Object> getProdResultDetail(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrPk", jrPk);

		String sql = """
			WITH target AS (
				SELECT jr.id AS child_id, jr."Parent_id" AS parent_id, jr."Description"
				FROM job_res jr
				WHERE jr.id = :jrPk
			),
			base_pick AS (
				SELECT COALESCE(parent_id, child_id) AS base_id
				FROM target
			)
			SELECT
				-- PK들(프런트에서 쓰기 좋게 모두 내려줌)
				c.id                             AS id,              -- ★ child jr_pk (현재 상세의 주인공)
				t.parent_id                      AS parent_jr_pk,    -- 부모 있으면 부모 pk
				b.base_id                        AS base_jr_pk,      -- 부모가 있으면 부모, 없으면 자기 자신
		
				-- 기본 정보는 base 기준(=부모 우선)
				c."WorkOrderNumber"              AS order_num,       -- 작업지시번호는 child/parent 동일하므로 child 써도 무방
				base_m.id                        AS mat_pk,
				base_m."Code"                    AS mat_code,
				base_m."Name"                    AS mat_name,
				base_m."LotSize"                 AS lot_size,
				u."Name"                         AS unit,
				ROUND(COALESCE(base_jr."OrderQty", 0)::numeric, 2)   AS order_qty,
				 ROUND(COALESCE(base_jr."GoodQty", 0)::numeric, 2)    AS good_qty,
				 ROUND(COALESCE(base_jr."DefectQty", 0)::numeric, 2)  AS defect_qty,
				 ROUND(COALESCE(base_jr."LossQty", 0)::numeric, 2)    AS loss_qty,
				 ROUND(COALESCE(base_jr."ScrapQty", 0)::numeric, 2)   AS scrap_qty,
				to_char(base_jr."ProductionDate",'yyyy-mm-dd') AS prod_date,
				to_char(c."StartTime",'hh24:mi')         AS start_time,
				c."EndDate"                               AS end_date,
				to_char(c."StartTime",'yyyy-mm-dd')       AS start_date,
				to_char(c."EndTime",'hh24:mi')            AS end_time,
				c."ShiftCode"                             AS shift_code,
				sh."Name"                                       AS shift_name,
				base_m."ValidDays",
				base_m."Routing_id"                             AS routing_id,
				base_m."Temperature" as mat_temp,
				base_m."Pressure" as mat_rpm,
		
				-- 공정/워크센터/설비/상태는 child 기준(=현재 공정)
				c."State"                                       AS state,
				fn_code_name('job_state', c."State")            AS job_state,
				child_wc.id                                     AS workcenter_id,
				child_wc."Name"                                 AS workcenter_name,
				child_wc."Factory_id"                           AS wcfactory_id,
				e.id                                            AS equipment_id,
				e."Name"                                        AS equipment_name,
				child_p.id                                      AS process_id,
				child_p."Name"                                  AS process_nm,
		
				-- 필요하면 정렬/표시용
				base_jr."WorkIndex"                             AS work_idx,
				c."LotNumber"                                   AS lot_num,
				t."Description" as description
		
			FROM target t
			JOIN base_pick b                 ON 1=1
			JOIN job_res c                   ON c.id = t.child_id              -- child
			JOIN job_res base_jr             ON base_jr.id = b.base_id         -- base(부모 있으면 부모)
			LEFT JOIN material base_m        ON base_m.id = base_jr."Material_id"
			LEFT JOIN unit u                 ON u.id = base_m."Unit_id"
			LEFT JOIN shift sh               ON sh."Code" = base_jr."ShiftCode"
			LEFT JOIN work_center child_wc   ON child_wc.id = c."WorkCenter_id"
			LEFT JOIN process child_p        ON child_p.id = child_wc."Process_id"
			LEFT JOIN equ e                  ON e.id = c."Equipment_id"
			""";

		return this.sqlRunner.getRow(sql, p);
	}

	public Map<String, Object> getProdResultMatDetail(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrPk", jrPk);

		String sql = """
			select jr.id
			, m."Code" as mat_code
			, m."Name" as mat_name
			, ROUND(jr."OrderQty"::numeric, 2) as "OrderQty"
			, sju."Standard" as standard
			, sju.id as suju_id
			from job_res jr 
			inner join material m on m.id = jr."Material_id" 
			LEFT JOIN suju sju ON sju.id = jr."SourceDataPk"
			where jr.id = :jrPk
			and jr."SourceTableName" ='suju'
			""";

		Map<String, Object> job = this.sqlRunner.getRow(sql, p);
		if (job == null) return null;

		// ② 하위 품목 리스트(suju_detail)
		MapSqlParameterSource p2 = new MapSqlParameterSource().addValue("suju_id", job.get("suju_id"));
		String sql_suju_detail = """
			SELECT
				sd.id,
				sd."suju_id",
				sd."Standard",
				sd."Qty"
			FROM suju_detail sd
			WHERE sd."suju_id" = :suju_id
			ORDER BY sd.id
		""";
		List<Map<String, Object>> suju_detail = this.sqlRunner.getRows(sql_suju_detail, p2);

		// ③ items 키로 리스트 추가 (print_report 에서 {%= o.items %})
		job.put("items", suju_detail);

		return job;
	}

	public Map<String, Object> getProdResultPrintDetail(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrPk", jrPk);

		String sql = """
			WITH target AS (
				SELECT jr.id AS child_id, jr."Parent_id" AS parent_id, jr."Description" as memo
				FROM job_res jr
				WHERE jr.id = :jrPk
			),
			base_pick AS (
				SELECT COALESCE(parent_id, child_id) AS base_id
				FROM target
			)
			SELECT
				-- PK들(프런트에서 쓰기 좋게 모두 내려줌)
				c.id                             AS id,              -- ★ child jr_pk (현재 상세의 주인공)
				t.parent_id                      AS parent_jr_pk,    -- 부모 있으면 부모 pk
				b.base_id                        AS base_jr_pk,      -- 부모가 있으면 부모, 없으면 자기 자신
		
				-- 기본 정보는 base 기준(=부모 우선)
				c."WorkOrderNumber"              AS order_num,       -- 작업지시번호는 child/parent 동일하므로 child 써도 무방
				base_m.id                        AS mat_pk,
				base_m."Code"                    AS mat_code,
				base_m."Name"                    AS mat_name,
				base_m."LotSize"                 AS lot_size,
				u."Name"  AS unit,
				ROUND(COALESCE(base_jr."OrderQty", 0)::numeric, 2)   AS order_qty,
				 ROUND(COALESCE(base_jr."GoodQty", 0)::numeric, 2)    AS good_qty,
				 ROUND(COALESCE(base_jr."DefectQty", 0)::numeric, 2)  AS defect_qty,
				 ROUND(COALESCE(base_jr."LossQty", 0)::numeric, 2)    AS loss_qty,
				 ROUND(COALESCE(base_jr."ScrapQty", 0)::numeric, 2)   AS scrap_qty,
				to_char(base_jr."ProductionDate",'yyyy-mm-dd') AS prod_date,
				to_char(s."DueDate" ,'yyyy-mm-dd') AS due_date,
				to_char(c."StartTime",'hh24:mi')   AS start_time,
				c."EndDate" AS end_date,
				to_char(c."StartTime",'yyyy-mm-dd') AS start_date,
				to_char(c."EndTime",'hh24:mi')  AS end_time,
				c."ShiftCode"  AS shift_code,
				sh."Name"   AS shift_name,
				base_m."ValidDays",
				base_m."Routing_id"  AS routing_id,
		
				-- 공정/워크센터/설비/상태는 child 기준(=현재 공정)
				c."State"  AS state,
				fn_code_name('job_state', c."State")   AS job_state,
				child_wc.id   AS workcenter_id,
				child_wc."Name"  AS workcenter_name,
				e.id   AS equipment_id,
				e."Name"  AS equipment_name,
				child_p.id AS process_id,
				child_p."Name" AS process_nm,
		
				-- 필요하면 정렬/표시용
				base_jr."WorkIndex" AS work_idx,
				c."LotNumber" AS lot_num,
				base_jr."SourceDataPk" AS suju_id,
				s."CompanyName" as company_name,
				s."Standard" as standard, 
				t.memo
		
			FROM target t
			JOIN base_pick b                 ON 1=1
			JOIN job_res c                   ON c.id = t.child_id              -- child
			JOIN job_res base_jr             ON base_jr.id = b.base_id         -- base(부모 있으면 부모)
			LEFT JOIN material base_m        ON base_m.id = base_jr."Material_id"
			LEFT JOIN unit u                 ON u.id = base_m."Unit_id"
			LEFT JOIN shift sh               ON sh."Code" = base_jr."ShiftCode"
			LEFT JOIN work_center child_wc   ON child_wc.id = c."WorkCenter_id"
			LEFT JOIN process child_p        ON child_p.id = child_wc."Process_id"
			LEFT JOIN equ e                  ON e.id = c."Equipment_id"
			left join suju s on s.id = base_jr."SourceDataPk" and base_jr."SourceTableName" = 'suju'
			""";

		Map<String, Object> job = this.sqlRunner.getRow(sql, p);
		if (job == null) return null;

		// ② 하위 품목 리스트(suju_detail)
		MapSqlParameterSource p2 = new MapSqlParameterSource().addValue("suju_id", job.get("suju_id"));
		String sql_suju_detail = """
			SELECT
				sd.id,
				sd."suju_id",
				sd."Standard",
				sd."Qty"
			FROM suju_detail sd
			WHERE sd."suju_id" = :suju_id
			ORDER BY sd.id
		""";
		List<Map<String, Object>> suju_detail = this.sqlRunner.getRows(sql_suju_detail, p2);

		// ③ items 키로 리스트 추가 (print_report 에서 {%= o.items %})
		job.put("items", suju_detail);

		// ④ title 추가
		job.put("title", "제 품 확 인 서");

		return job;
	}


	public List<Map<String, Object>> getDefectList(Integer jrPk, Integer workcenterId) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);
		dicParam.addValue("workcenterId", workcenterId);

		String sql = """
                 with TOT as (
                          select jrd.id as jrd_id
                          , jrd."DefectQty" as defect_qty
                          , jrd."DefectType_id"  as defect_id
                          , jrd."Description" as defect_remark
                          from job_res_defect jrd 
                          where jrd."JobResponse_id" = :jrPk
                       ), a as(
                         select 
                         jr."WorkCenter_id"
                         , wc."Process_id"
                         , pdt."DefectType_id" as defect_id
                         , dt."Name" as defect_type
                         , coalesce( TOT.defect_qty,0) as defect_qty
                         , TOT.jrd_id
                         , TOT.defect_remark
                         from job_res jr 
                         left join work_center wc on wc.id=jr."WorkCenter_id"  
                         left join proc_defect_type pdt on pdt."Process_id" =wc."Process_id" 
                         inner join defect_type dt on dt.id = pdt."DefectType_id" 
                         left join TOT on TOT.defect_id=dt.id
                         where jr.id = :jrPk
                         )
                         select * from a
                """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getChasuList(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrPk", jrPk);
		String sql = """
        SELECT mp.id
             , mp."LotIndex" AS chasu
             , mp."LotNumber" AS lot_no
             , mp."State" AS state
             , CASE mp."State"
                 WHEN 'ready'    THEN '배정'
                 WHEN 'working'  THEN '진행중'
                 WHEN 'finished' THEN '완료'
                 ELSE mp."State"
               END AS state_name
             , ROUND(COALESCE(mp."InputQty",0)::numeric, 2)  AS input_qty
             , ROUND(COALESCE(mp."GoodQty",0)::numeric, 2)   AS good_qty
             , ROUND(COALESCE(mp."DefectQty",0)::numeric, 2) AS defect_qty
             , per."Name" AS worker_name
             , to_char(mp."StartTime", 'MM-DD HH24:MI') AS start_time
             , to_char(mp."EndTime",   'MM-DD HH24:MI') AS end_time
        FROM mat_produce mp
        LEFT JOIN person per ON per.id = mp."Actor_id"
        WHERE mp."JobResponse_id" = :jrPk
        ORDER BY mp."LotIndex"
    """;
		return this.sqlRunner.getRows(sql, p);
	}

	public List<Map<String, Object>> getInputLotList(Integer jrPk, String mat_code) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);
		dicParam.addValue("mat_code", mat_code);

		String sql = """
                with AA as (
                         select 
                         ml."LotNumber"
                         , sum(mlc."OutputQty") as "OutputQty" 
                         from mat_produce mp 
                         inner join job_res jr on jr.id = mp."JobResponse_id"
                         inner join mat_lot_cons mlc on mlc."SourceDataPk" = mp.id and mlc."SourceTableName" ='mat_produce'   
                         inner join mat_lot ml on ml.id = mlc."MaterialLot_id" 
                         where jr.id= :jrPk group by ml."LotNumber" 
                         ), R as (
                             select  mpir.id as mpir_id
                             , mpi.id as mpi_id
                             , mpi."Material_id" as mat_pk
                             , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
                             , mg."Name" as mat_group_name
                             , m."Code" as mat_code
                             , m."Name" as mat_name 
                             , u."Name" as unit_name
                             , mpi."RequestQty" as req_qty
                             , mpi."InputQty" 
                             , to_char(mpi."InputDateTime",'yyyy-MM-dd') as "InputDateTime"
                             , ml."LotNumber"
                             , ml."CurrentStock" as cur_stock
                             , m."ProcessSafetyStock" as proc_safety_stock
                             , mpi."MaterialStoreHouse_id"
                             , mpi."ProcessStoreHouse_id"
                             , mpi."State"
                             , fn_code_name('mat_proc_input_state', mpi."State") as state_name
                             , sh."Name" as "StoreHouseName"
                             from job_res jr 
                             inner join mat_proc_input_req mpir on mpir.id = jr."MaterialProcessInputRequest_id" 
                             inner join mat_proc_input mpi on mpi."MaterialProcessInputRequest_id" =mpir.id
                             inner join material m on m.id = mpi."Material_id"
                             inner join mat_grp mg on mg.id = m."MaterialGroup_id"
                             left join unit u on u.id = m."Unit_id"
                             left join mat_lot ml on ml.id = mpi."MaterialLot_id"
                             left join store_house sh on sh.id=ml."StoreHouse_id"
                             where jr.id =  :jrPk
                             and (:mat_code is null or :mat_code = '' or m."Code" = :mat_code)
                          )
                          select R.mat_pk, R.mat_type_name, R.mat_group_name, R.mat_code, R.mat_name
                          , R.mpir_id
                          , R.mpi_id
                          , R.req_qty
                          , R."InputQty" 
                          , R."LotNumber" as lot_number
                          , R.state_name
                          , R.unit_name
                          , R.cur_stock
                          , R."State" 
                          , R."InputDateTime" as start_date
                          , R."StoreHouseName"
                          , COALESCE(AA."OutputQty", 0) as consumed_qty
                          from R 
                          left join AA on AA."LotNumber" = R."LotNumber"
                          order by R."InputDateTime", R."LotNumber"
                	""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public Integer findJobByOrderAndProcess(String orderNum, Integer processId, Integer proMatId) {
		MapSqlParameterSource p = new MapSqlParameterSource()
				.addValue("order_num", orderNum)
				.addValue("process_id", processId)
				.addValue("pro_mat_id", proMatId);
		String sql = """
				SELECT jr.id
				FROM job_res jr
				JOIN work_center wc ON wc.id = jr."WorkCenter_id"
				WHERE jr."WorkOrderNumber" = :order_num
				  AND wc."Process_id" = :process_id
				  AND jr."Material_id" = :pro_mat_id
				ORDER BY jr.id DESC
				LIMIT 1;
				""";
		Map<String,Object> row = sqlRunner.getRow(sql, p);
		return row != null ? (Integer) row.get("id") : null;
	}

	public List<Map<String, Object>> getConsumedListPlan(Integer prodMatId, BigDecimal needProMatQty, String prodDate) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("prodMatId", prodMatId);
		p.addValue("needQty", needProMatQty);
		p.addValue("prodDate", prodDate);

		String sql = """
        WITH bom1 AS (
            SELECT
                b1.id AS bom_pk,
                b1."Material_id" AS prod_pk,
                b1."OutputAmount" AS produced_qty,
                :needQty::numeric AS order_qty,
                ROW_NUMBER() OVER (PARTITION BY b1."Material_id" ORDER BY b1."Version" DESC) AS g_idx
            FROM bom b1
            WHERE b1."BOMType" = 'manufacturing'
              AND (:prodDate::date IS NULL OR :prodDate::date BETWEEN b1."StartDate" AND b1."EndDate")
              AND b1."Material_id" = :prodMatId
        ),
        BT AS (
            SELECT
                bc."Material_id" AS mat_pk,
                b.produced_qty,
                bc."Amount" AS quantity,
                (bc."Amount" / NULLIF(b.produced_qty,0)) AS bom_ratio,
                (bc."Amount" / NULLIF(b.produced_qty,0)) * b.order_qty AS bom_requ_qty
            FROM bom_comp bc
            JOIN bom1 b ON b.bom_pk = bc."BOM_id"
            WHERE b.g_idx = 1
        )
        SELECT
            BT.mat_pk,
            mg."MaterialType" AS mat_type,
            fn_code_name('mat_type', mg."MaterialType") AS mat_type_name,
            mg."Name" AS mat_group_name,
            m."Code" AS mat_code,
            m."Name" AS mat_name,
            m."LotSize" AS lot_size,
            mh."CurrentStock" AS "currentStock",
            u."Name" AS unit,
            BT.bom_ratio,
            ROUND(BT.bom_requ_qty::numeric) AS bom_consumed,   -- 예상 소요
            0::numeric AS consumed_qty,                        -- 아직 미시작이므로 0
            sh."Name" AS storehouse_name,
            0::numeric AS mc_qty,
            0::numeric AS current_qty_sum,
            COALESCE(m."LotUseYN",'N') AS "lotUseYn",
            CASE WHEN m."Useyn"='1' THEN 'Y' WHEN m."Useyn"='0' THEN 'N' ELSE NULL END AS useyn
        FROM BT
        JOIN material m   ON m.id = BT.mat_pk
        LEFT JOIN mat_grp mg  ON mg.id = m."MaterialGroup_id"
        LEFT JOIN unit u      ON u.id = m."Unit_id"
        LEFT JOIN store_house sh ON sh.id = m."StoreHouse_id"
        LEFT JOIN mat_in_house mh ON mh."Material_id" = m.id AND mh."StoreHouse_id" = m."StoreHouse_id"
        WHERE m."Useyn" = '0'
        ORDER BY m."Code"
    """;

		return this.sqlRunner.getRows(sql, p);
	}



	public List<Map<String, Object>> getConsumedListFirst(Integer jrPk, Integer prodPk, String prodDate) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);
		dicParam.addValue("prodPk", prodPk);
		dicParam.addValue("prodDate", prodDate);

		String sql = """
                with bom1 as (
						select 
						b1.id as bom_pk
						, b1."Material_id" as prod_pk
						, b1."OutputAmount" as produced_qty
						, jr."OrderQty" as order_qty
						, row_number() over(partition by b1."Material_id" order by b1."Version" desc) as g_idx
						from bom b1
						 inner join job_res jr on jr."Material_id"=b1."Material_id" and jr.id= :jrPk
						where b1."BOMType" = 'manufacturing' and jr."ProductionDate" between b1."StartDate" and b1."EndDate"  
						), BT as (
						select 
						bc."Material_id" as mat_pk
						, round(bom1.produced_qty::numeric, 0) as produced_qty
				    	, round(bc."Amount"::numeric, 0) as quantity
				    	, round((bc."Amount" / bom1.produced_qty)::numeric, 0) as bom_ratio
				    	, round((bc."Amount" / bom1.produced_qty * bom1.order_qty)::numeric, 0) as bom_requ_qty 
						from bom_comp bc 
						inner join bom1 on bom1.bom_pk=bc."BOM_id"
						where bom1.g_idx=1
						), llc as (
						select 
						sum(mlc."OutputQty") as consumed_qty
						, ml."Material_id" 
						from job_res jr 
						inner join mat_produce mp on mp."JobResponse_id" =jr.id and jr.id= :jrPk
						inner join mat_lot_cons mlc on mlc."SourceDataPk" =mp.id and mlc."SourceTableName" ='mat_produce'
						inner join mat_lot ml on ml.id = mlc."MaterialLot_id" 
						group by ml."Material_id" 
						), MCC as (
							select 
							mc."Material_id" as mat_pk
							, sum(mc."ConsumedQty") mc_qty 
							from mat_consu mc 
							where mc."JobResponse_id"= :jrPk group by mc."Material_id"
						), MMP as (
							select 
							sum(ml."OutQtySum") as current_qty_sum
							, mpi."Material_id"
							, sum(round(mpi."RequestQty"::numeric, 0)) as request_qty_sum
							from mat_proc_input mpi
							inner join job_res jr on jr."MaterialProcessInputRequest_id" = mpi."MaterialProcessInputRequest_id" 
							inner join mat_lot ml on ml.id = mpi."MaterialLot_id"
							where jr.id=:jrPk
							group by mpi."Material_id"
						)
						select 
						BT.mat_pk
						, mg."MaterialType" as mat_type
						, fn_code_name('mat_type', mg."MaterialType") as mat_type_name
						, mg."Name" as mat_group_name
						, m."Code" as mat_code
						, m."Name" as mat_name
						, m."LotSize" as lot_size
						, mh."CurrentStock" as "currentStock"
						, u."Name" as unit
						, BT.bom_ratio
						, round(BT.bom_requ_qty::numeric, 4) as bom_consumed
						, COALESCE(llc.consumed_qty,0) as consumed_qty
						, MMP.request_qty_sum
						,round(
				  			(coalesce(round(BT.bom_requ_qty::numeric, 0), 0)   -- = bom_consumed과 동일
				  			- coalesce(round(MMP.request_qty_sum::numeric, 0), 0)
				  			)
						, 4) as remain_input_qty
						, sh."Name" as storehouse_name
						, MCC.mc_qty
						, COALESCE(MMP.current_qty_sum,0) as current_qty_sum
						, coalesce(m."LotUseYN",'N') as "lotUseYn"
						, MMP.request_qty_sum
						,round(
						   (
							 coalesce(round(BT.bom_requ_qty::numeric, 0), 0)   -- = bom_consumed과 동일
						   - coalesce(round(MMP.request_qty_sum::numeric, 0), 0)
						   )
						 , 3) as remain_input_qty
						, CASE WHEN m."Useyn" = '1' THEN 'Y'
							   WHEN m."Useyn" = '0' THEN 'N'
							   ELSE NULL
						  END as useyn
						from BT
						inner join material m on m.id=BT.mat_pk
						left join MCC on MCC.mat_pk=BT.mat_pk
						left join mat_grp mg on mg.id=m."MaterialGroup_id"
						left join unit u on u.id=m."Unit_id"
						left join llc on llc."Material_id" = BT.mat_pk
						left join store_house sh on m."StoreHouse_id" = sh.id
						left join mat_in_house mh on mh."Material_id" = m.id and mh."StoreHouse_id"  = m."StoreHouse_id" 
						left join MMP on MMP."Material_id" = m.id
						where m."Useyn" = '0'
                """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public Map<String, Object> getProcessStepMeta(
			Integer routingId, Integer processId, Integer materialId, BigDecimal order_qty, String prodDate) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("processId", processId);
		p.addValue("materialId", materialId);
		p.addValue("orderQty", order_qty);
		p.addValue("prodDate", prodDate);

		String sql = """
			-- inputs: :materialId(루트 품목), :processId, :prodDate, :orderQty [, :routingId]
			 WITH RECURSIVE walk AS (
			   -- 루트의 유효/최신 제조 BOM
			   WITH root_bom AS (
				 SELECT b1.id AS bom_pk,
						b1."Material_id"          AS node_mat_id,
						b1."OutputAmount"::numeric AS node_out,        -- ★ numeric 고정
						ROW_NUMBER() OVER (PARTITION BY b1."Material_id" ORDER BY b1."Version" DESC) AS rn
				 FROM bom b1
				 WHERE b1."BOMType" = 'manufacturing'
				   AND :prodDate::date BETWEEN b1."StartDate" AND b1."EndDate"
				   AND b1."Material_id" = :materialId
			   )
			   SELECT rb.bom_pk,
					  rb.node_mat_id,
					  rb.node_out,                                     -- ★ numeric
					  NULL::integer AS parent_bom_pk,
					  NULL::integer AS parent_mat_pk,
					  1 AS lvl,
					  1::numeric AS cum_ratio                          -- ★ numeric로 시작
			   FROM root_bom rb
			   WHERE rb.rn = 1
			 
			   UNION ALL
			 
			   -- 하위 확장: (자식 소요 / 부모 산출) 비율 누적
			   SELECT child.bom_pk,
					  child.mat_id           AS node_mat_id,
					  child.out_amt::numeric AS node_out,              -- ★ numeric
					  w.bom_pk               AS parent_bom_pk,
					  w.node_mat_id          AS parent_mat_pk,
					  w.lvl + 1              AS lvl,
					  ( w.cum_ratio
						* ( bc."Amount"::numeric / NULLIF(w.node_out,0)::numeric )
					  )::numeric AS cum_ratio                          -- ★ 재귀식도 numeric
			   FROM walk w
			   JOIN bom_comp bc
				 ON bc."BOM_id" = w.bom_pk
			   JOIN LATERAL (
				 SELECT b2.id AS bom_pk,
						b2."Material_id" AS mat_id,
						b2."OutputAmount"::numeric AS out_amt          -- ★ numeric
				 FROM bom b2
				 WHERE b2."BOMType" = 'manufacturing'
				   AND :prodDate::date BETWEEN b2."StartDate" AND b2."EndDate"
				   AND b2."Material_id" = bc."Material_id"
				 ORDER BY b2."Version" DESC
				 LIMIT 1
			   ) child ON TRUE
			 ),
			 targets AS (  -- 선택 공정에 해당하는 산출품 후보
			   SELECT
				 w.node_mat_id                     AS pro_mat_id,
				 MIN(w.bom_pk)                     AS bom_id,
				 MIN(w.parent_bom_pk)              AS parent_bom_id,
				 MIN(w.lvl)                        AS lvl,
				 SUM(w.cum_ratio)::numeric         AS ratio_from_root  -- ★ numeric
			   FROM walk w
			   JOIN material m     ON m.id  = w.node_mat_id
			   JOIN work_center wc ON wc.id = m."WorkCenter_id"
			   WHERE wc."Process_id" = :processId
			   GROUP BY w.node_mat_id
			 )
			 SELECT
			   t.pro_mat_id,
			   m."Name" AS pro_mat_nm,
			   t.bom_id,
			   t.parent_bom_id,
			   t.ratio_from_root,
			   ( :orderQty::numeric * COALESCE(t.ratio_from_root,0) )::numeric AS need_pro_mat_qty  -- ★ 최상위 지시량 적용
			 FROM targets t
			 LEFT JOIN material m ON m.id = t.pro_mat_id
			 ORDER BY t.lvl;
	  """;

		return this.sqlRunner.getRow(sql, p);
	}

	public List<Map<String, Object>> getConsumedByProcess(
			Integer routingId, Integer processId, Integer materialId, BigDecimal order_qty, String prodDate) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("processId", processId);
		p.addValue("materialId", materialId);
		p.addValue("orderQty", order_qty);
		p.addValue("prodDate", prodDate);

		String sql = """
				WITH bd AS (
						SELECT * FROM tbl_bom_detail(:materialId::varchar, :prodDate)
					  ),
					  root AS (SELECT DISTINCT prod_pk FROM bd),
					  sfg_by_parent AS (
						SELECT DISTINCT bd.parent_mat_pk AS sfg_mat_pk
						FROM bd
						JOIN material pm   ON pm.id = bd.parent_mat_pk
						JOIN work_center wc ON wc.id = pm."WorkCenter_id"
						WHERE bd.parent_mat_pk IS NOT NULL
						  AND wc."Process_id" = :processId
					  ),
					  sfg_by_root AS (
						SELECT r.prod_pk AS sfg_mat_pk
						FROM root r
						JOIN material rm   ON rm.id = r.prod_pk
						JOIN work_center wc ON wc.id = rm."WorkCenter_id"
						WHERE wc."Process_id" = :processId
					  ),
					  sfg AS (SELECT sfg_mat_pk FROM sfg_by_parent UNION SELECT sfg_mat_pk FROM sfg_by_root),
					  
					  -- 필요자재(직계)
					  components AS (
						SELECT bd.*
						FROM bd
						JOIN sfg s ON
							 bd.parent_mat_pk = s.sfg_mat_pk
							 OR (bd.parent_mat_pk IS NULL AND bd.prod_pk = s.sfg_mat_pk) -- 루트 공정
					  )
					  SELECT
						(SELECT MIN(bd2.bom_pk) FROM bd bd2 WHERE bd2.parent_mat_pk = c.parent_mat_pk) AS bom_id,
						(SELECT MIN(bd3.parent_bom_pk) FROM bd bd3 WHERE bd3.mat_pk = c.parent_mat_pk) AS parent_bom_id,
						c.parent_mat_pk                             AS pro_mat_id,
						c.mat_pk                                    AS component_id,
						m."Code"                                    AS component_code,
						m."Name"                                    AS component_name,
						u."Name"                                    AS unit,
						c.bom_ratio                                 AS bom_ratio_from_root,
						ROUND((c.bom_ratio * :orderQty)::numeric)   AS need_qty
					  FROM components c
					  JOIN material m ON m.id = c.mat_pk
					  LEFT JOIN unit u ON u.id = m."Unit_id"
					  WHERE m."Useyn" = '0'
					  ORDER BY m."Code";
					  
	  """;

		return this.sqlRunner.getRows(sql, p);
	}

	public List<Map<String, Object>> getConsumedByRoutingProcess(
			Integer routingId, Integer processId, Integer materialId, BigDecimal order_qty, String prodDate) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("processId", processId);
		p.addValue("materialId", materialId);
		p.addValue("orderQty", order_qty);
		p.addValue("prodDate", prodDate);

		String sql = """
				WITH bd AS (
						SELECT * FROM tbl_bom_detail(:materialId::varchar, :prodDate)
					  ),
					  root AS (SELECT DISTINCT prod_pk FROM bd),
					  sfg_by_parent AS (
						SELECT DISTINCT bd.parent_mat_pk AS sfg_mat_pk
						FROM bd
						JOIN material pm   ON pm.id = bd.parent_mat_pk
						JOIN work_center wc ON wc.id = pm."WorkCenter_id"
						WHERE bd.parent_mat_pk IS NOT NULL
						  AND wc."Process_id" = :processId
					  ),
					  sfg_by_root AS (
						SELECT r.prod_pk AS sfg_mat_pk
						FROM root r
						JOIN material rm   ON rm.id = r.prod_pk
						JOIN work_center wc ON wc.id = rm."WorkCenter_id"
						WHERE wc."Process_id" = :processId
					  ),
					  sfg AS (SELECT sfg_mat_pk FROM sfg_by_parent UNION SELECT sfg_mat_pk FROM sfg_by_root),
					  
					  -- 필요자재(직계)
					  components AS (
						SELECT bd.*
						FROM bd
						JOIN sfg s ON
							 bd.parent_mat_pk = s.sfg_mat_pk
							 OR (bd.parent_mat_pk IS NULL AND bd.prod_pk = s.sfg_mat_pk) -- 루트 공정
					  )
					  SELECT
						(SELECT MIN(bd2.bom_pk) FROM bd bd2 WHERE bd2.parent_mat_pk = c.parent_mat_pk) AS bom_id,
						(SELECT MIN(bd3.parent_bom_pk) FROM bd bd3 WHERE bd3.mat_pk = c.parent_mat_pk) AS parent_bom_id,
						c.parent_mat_pk                             AS pro_mat_id,
						c.mat_pk                                    AS component_id,
						m."Code"                                    AS component_code,
						m."Name"                                    AS component_name,
						u."Name"                                    AS unit,
						c.bom_ratio                                 AS bom_ratio_from_root,
						ROUND((c.bom_ratio * :orderQty)::numeric)   AS need_qty
					  FROM components c
					  JOIN material m ON m.id = c.mat_pk
					  LEFT JOIN unit u ON u.id = m."Unit_id"
					  WHERE m."Useyn" = '0'
					  ORDER BY m."Code";
					  
	  """;

		return this.sqlRunner.getRows(sql, p);
	}


	public List<Map<String, Object>> getConsumedListSecond(Integer jrPk, Integer prodPk, String prodDate) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("jrPk", jrPk);
		dicParam.addValue("prodPk", prodPk);
		dicParam.addValue("prodDate", prodDate);

		String sql = """
                with A as (
                                select 
                                l."Material_id" as mat_id
                                , sum(lc."OutputQty") as lot_consumed
                                from job_res jr
                                inner join mat_produce mp on mp."JobResponse_id" = jr.id 
                                inner join mat_lot_cons lc on lc."SourceDataPk" = mp.id
                                inner join mat_lot l on l.id = lc."MaterialLot_id" 
                                where lc."SourceTableName" = 'mat_produce'
                                and jr.id = :jrPk
                                group by l."Material_id"
                            )
                            select m.id as mat_pk
                            , m."Name" as mat_name
                            , u."Name" as unit
                            , fn_unit_ceiling( bom.bom_ratio * , u."PieceYN" ) as bom_consumed
                            , A.lot_consumed
                            , A.lot_consumed as consumed
                            from tbl_bom_detail(cast(:prodPk as text), cast(to_char(cast(:prodDate as date),'YYYY-MM-DD') as text)) as bom
                            inner join material m on m.id = bom.mat_pk
                            left join unit u on u.id = m."Unit_id"
                            left join A on A.mat_id = m.id
                            where bom.b_level = 1
                            order by tot_order 
                """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> prodTestList(Integer jrPk, Integer testResultId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("jrPk", jrPk);
		param.addValue("testResultId", testResultId);

		String sql = """
                	select ti.id, up."Name" as "CheckName", ti."ResultType" as "resultType"
                	, tim."SpecText" as "specText"
                	, to_char(tir."TestDateTime", 'YYYY-MM-DD') as "testDate"
                	, tir."JudgeCode", tir."InputResult" as "ctRemark" ,tir."CharResult" as "ntRemark" , ti."Name" as name 
                	, tir."Char1" as result1, tir."Char2" as result2
                	, tr.id as "testResultId", tr."TestMaster_id" as "testMasterId"
                	from test_item_result tir
                	inner join test_result tr on tr.id = tir."TestResult_id"
                	inner join test_mast tm on tm.id = tr."TestMaster_id" 
                	inner join test_item ti on tir."TestItem_id"  = ti.id 
                	inner join test_item_mast tim on ti.id = tim."TestItem_id" and tim."TestMaster_id" = tm.id
                	inner join user_profile up on tir."_creater_id"  = up."User_id" 
                	where tr."SourceTableName" = 'job_res' and tr."SourceDataPk" = :jrPk
                	and tr.id = :testResultId
                	order by ti.id
                """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public List<Map<String, Object>> prodTestDefaultList() {

		String sql = """
                select ti.id, ti."Name" as name , ti."ResultType" as "resultType", tim."SpecText" as "specText", '' as result1, '' as result2 
                from test_item_mast tim 
                inner join test_mast tm on tim."TestMaster_id"  = tm.id 
                inner join test_item ti on tim."TestItem_id"  = ti.id
                where tm."Name"  = '제품검사'
                   """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, null);

		return items;
	}

	public Integer getTestMasterByItem(Integer jrPk) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("jrPk", jrPk);

		String sql = """
                    SELECT tmm."TestMaster_id" AS testMasterId
                            FROM job_res jr
                            INNER JOIN test_mast_mat tmm ON jr."Material_id" = tmm."Material_id"
                            WHERE jr.id = :jrPk
                            LIMIT 1
                """;

		List<Map<String, Object>> result = this.sqlRunner.getRows(sql, param);
		return result.isEmpty() ? null : (Integer) result.get(0).get("testMasterId");
	}


	public List<Map<String, Object>> prodTestListByTestMaster(Integer testMasterId) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("testMasterId", testMasterId);

		String sql = """
                    SELECT tm.id AS testMasterId, ti.id, ti."Name" AS name, ti."ResultType" AS "resultType",
                           tim."SpecText" AS "specText", '' AS result1, '' AS result2
                    FROM test_item_mast tim
                    INNER JOIN test_mast tm ON tim."TestMaster_id" = tm.id
                    INNER JOIN test_item ti ON tim."TestItem_id" = ti.id
                    WHERE tm.id = :testMasterId
                """;

		return this.sqlRunner.getRows(sql, param);
	}


	public List<Map<String, Object>> getMaterialProcessInputList(int jrPk, int matPk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("jrPk", jrPk);
		param.addValue("matPk", matPk);

		String sql = """
                select  mpi.id  as mpi_id
                	  ,	mpi."RequestQty" as req_qty
                	  , mpi."InputQty" as input_qty
                	  , mpi."Material_id" as mat_pk
                	  , ml."CurrentStock" as curr_qty
                	  , ml.id as ml_id
                	  , ml."LotNumber"
                	  , ml."EffectiveDate" as eff_date
                from job_res jr 
                inner join mat_proc_input mpi on mpi."MaterialProcessInputRequest_id"  = jr."MaterialProcessInputRequest_id"
                inner join mat_lot ml on ml.id = mpi."MaterialLot_id" 
                where jr.id = :jrPk
                and mpi."Material_id" = :matPk
                order by ml."EffectiveDate"
                   """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		return items;
	}

	public Map<String, Object> getJobResponseGoodDefectQty(Integer jrPk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("jrPk", jrPk);

		String sql = """
                select jr.id
                	  ,coalesce(sum(mp."GoodQty"),0) as good_qty
                	  ,coalesce(sum(mp."DefectQty"),0) as defect_qty
                from job_res jr 
                inner join mat_produce mp on mp."JobResponse_id" = jr.id 
                where jr.id = :jrPk
                group by jr.id
                """;

		Map<String, Object> items = this.sqlRunner.getRow(sql, param);

		return items;
	}

	public float getChasuDefectQty(Integer jrPk) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("jrPk", jrPk);

		String sql = """
                select coalesce(sum(mp."DefectQty"),0) as defect_qty 
                from mat_produce mp 
                   			where mp."JobResponse_id" = :jrPk
                   		""";

		Map<String, Object> items = this.sqlRunner.getRow(sql, param);

		float qty = Float.parseFloat(items.get("defect_qty").toString());

		return qty;
	}

	public AjaxResult getJobResByProcess(String dateFrom, String dateTo,
										 String factory, String item, boolean includeComp, String processCode) {

		AjaxResult result = new AjaxResult();

		String sql = """
       WITH proc_wc AS (
       SELECT wc.id AS wc_id, p."Code" AS proc_code, p.id AS proc_id
       FROM work_center wc
       JOIN process p ON p.id = wc."Process_id"
       WHERE p."Code" = :processCode
    ),
    jr_with_order AS (
       SELECT jr.id,
             jr."WorkOrderNumber"   AS order_num,
             jr."State"             AS state,
             jr."OrderQty"          AS order_qty,
             jr."GoodQty"           AS good_qty,
             jr."DefectQty"         AS defect_qty,
             jr."ProductionDate"    AS prod_date,
             to_char(jr."StartTime", 'HH24:MI') AS start_time,
             to_char(jr."EndTime",   'HH24:MI') AS end_time,
             jr."EndDate"           AS end_date,
             jr."Parent_id",
             jr."Routing_id",
             jr."Material_id",
             jr."WorkCenter_id",
             jr."Equipment_id",
             jr."Manager_id"        AS manager_id,
             jr."Description"       AS description,
             rp."ProcessOrder",
             m."Code"      AS mat_code,
             m."Name"      AS mat_name,
             m."Standard1" AS standard,
             u."Name"      AS unit,
             pw.proc_code,
             wc."Name"  AS workcenter_name,
             per."Name" AS worker_name,
             e."Name"   AS equ_name,
             CASE jr."State"
                WHEN 'working'  THEN '생산중'
                WHEN 'finished' THEN '생산완료'
                WHEN 'stopped'  THEN '일시중지'
                WHEN 'wait'     THEN '대기'
                ELSE '작업지시'
             END AS job_state
       FROM job_res jr
       JOIN proc_wc pw ON pw.wc_id = jr."WorkCenter_id"
       LEFT JOIN material m ON m.id = jr."Material_id"
       LEFT JOIN unit u ON u.id = m."Unit_id"
       LEFT JOIN work_center wc ON wc.id = jr."WorkCenter_id"
       LEFT JOIN person per ON per.id = jr."Manager_id"
       LEFT JOIN equ e ON e.id = jr."Equipment_id"
       LEFT JOIN routing_proc rp ON rp."Routing_id" = jr."Routing_id"
          AND rp."Process_id" = pw.proc_id
       WHERE jr."Parent_id" IS NOT NULL
         AND (jr."ProductionDate" BETWEEN CAST(:dateFrom AS date) AND CAST(:dateTo AS date)
             OR jr."ProductionDate" IS NULL)
         -- ★ 품목 검색 (코드 또는 명, 부분일치)
         AND (:item = '' OR m."Code" ILIKE '%' || :item || '%' OR m."Name" ILIKE '%' || :item || '%')
         -- ★ '완료 포함' 해제 시 생산완료 제외
         AND (:includeComp OR jr."State" <> 'finished')
    )
    SELECT j.*,
       CASE WHEN EXISTS (
          SELECT 1 FROM job_res sibling
          JOIN work_center swc ON swc.id = sibling."WorkCenter_id"
          JOIN routing_proc srp ON srp."Routing_id" = sibling."Routing_id"
             AND srp."Process_id" = swc."Process_id"
          WHERE sibling."Parent_id" = j."Parent_id"
               AND sibling.id != j.id
               AND sibling."Material_id" = j."Material_id"
               AND srp."ProcessOrder" < j."ProcessOrder"
               AND sibling."State" != 'finished'
       ) THEN true ELSE false END AS _locked
    FROM jr_with_order j
    ORDER BY
       CASE j.state
          WHEN 'working'  THEN 1   -- 작업중
          WHEN 'stopped'  THEN 2   -- 일시중지
          WHEN 'finished' THEN 4   -- 생산완료
          ELSE 3                   -- 작업지시(ordered) · 대기 등
       END,
       order_num DESC, mat_code
   """;

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("processCode", processCode);
		param.addValue("dateFrom", dateFrom);
		param.addValue("dateTo", dateTo);
		param.addValue("item", item == null ? "" : item.trim());   // ★
		param.addValue("includeComp", includeComp);                // ★

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);

		result.success = true;
		result.data = items;
		return result;
	}

	public Map<String, Object> getProdResultDetailByChild(Integer jrPk) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("jrPk", jrPk);

		String sql = """
        SELECT
            c.id                              AS id,
            c."Parent_id"                     AS parent_jr_pk,
            c."WorkOrderNumber"               AS order_num,
            -- ★ child(공정 row) 자신의 품목/수량
            cm.id                             AS mat_pk,
            cm."Code"                         AS mat_code,
            cm."Name"                         AS mat_name,
            cu."Name"                         AS unit,
            ROUND(COALESCE(c."OrderQty",0)::numeric, 2)   AS order_qty,
            ROUND(COALESCE(c."GoodQty",0)::numeric, 2)    AS good_qty,
            ROUND(COALESCE(c."DefectQty",0)::numeric, 2)  AS defect_qty,
            to_char(c."ProductionDate",'yyyy-mm-dd')      AS prod_date,
            to_char(c."StartTime",'hh24:mi')              AS start_time,
            c."EndDate"                                    AS end_date,
            to_char(c."EndTime",'hh24:mi')                AS end_time,
            c."State"                                      AS state,
            fn_code_name('job_state', c."State")           AS job_state,
            c."Description"                                AS description,
            c."Routing_id"                                 AS routing_id,
            c."Manager_id"                                 AS manager_id,
            child_wc.id                                    AS workcenter_id,
            child_wc."Name"                                AS workcenter_name,
            child_wc."Factory_id"                          AS wcfactory_id,
            c."Equipment_id"                               AS equipment_id,
            child_p."Name"                                 AS process_nm
        FROM job_res c
        LEFT JOIN material cm        ON cm.id = c."Material_id"
        LEFT JOIN unit cu            ON cu.id = cm."Unit_id"
        LEFT JOIN work_center child_wc ON child_wc.id = c."WorkCenter_id"
        LEFT JOIN process child_p    ON child_p.id = child_wc."Process_id"
        WHERE c.id = :jrPk
    """;
		return this.sqlRunner.getRow(sql, p);
	}

	/** 이 공정이 라우팅의 첫 공정인지 (자재 차감 대상) */
	public boolean isFirstProcessOfRouting(Integer routingId, Integer processId) {
		if (routingId == null || processId == null) return true; // 라우팅 없으면 단일공정 취급 → 차감
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("processId", processId);
		String sql = """
        SELECT CASE WHEN rp."ProcessOrder" = (
                   SELECT MIN(rp2."ProcessOrder") FROM routing_proc rp2
                   WHERE rp2."Routing_id" = :routingId
               ) THEN true ELSE false END AS is_first
        FROM routing_proc rp
        WHERE rp."Routing_id" = :routingId AND rp."Process_id" = :processId
        LIMIT 1
    """;
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row != null && Boolean.TRUE.equals(row.get("is_first"));
	}

	/** 이 공정이 라우팅의 마지막 공정인지 (완성품 입고 대상) */
	public boolean isLastProcessOfRouting(Integer routingId, Integer processId) {
		if (routingId == null || processId == null) return true; // 라우팅 없으면 단일공정 → 입고
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("routingId", routingId);
		p.addValue("processId", processId);
		String sql = """
        SELECT CASE WHEN rp."ProcessOrder" = (
                   SELECT MAX(rp2."ProcessOrder") FROM routing_proc rp2
                   WHERE rp2."Routing_id" = :routingId
               ) THEN true ELSE false END AS is_last
        FROM routing_proc rp
        WHERE rp."Routing_id" = :routingId AND rp."Process_id" = :processId
        LIMIT 1
    """;
		Map<String, Object> row = this.sqlRunner.getRow(sql, p);
		return row != null && Boolean.TRUE.equals(row.get("is_last"));
	}

	/** 첫 공정이면 BOM 자재 차감 (mat_consu + mat_inout out) */
	public AjaxResult consumeBomForChasu(Integer mpId, JobRes jr, User user, String spjangcd) {
		AjaxResult result = new AjaxResult();
		result.success = true;

		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		Timestamp now = DateUtil.getNowTimeStamp();
		LocalDate date = LocalDate.now();
		LocalTime time = LocalTime.now();
		DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");

		List<Map<String, Object>> bomMatItems = this.get_chasu_bom_mat_qty_list(mpId);
		if (bomMatItems.isEmpty()) {
			result.success = false;
			result.message = "BOM구성이 없습니다.";
			return result;
		}

		for (Map<String, Object> bomMap : bomMatItems) {
			float chasuBomQty = Float.parseFloat(bomMap.get("chasu_bom_qty").toString());
			int consumeMatPk = (int) bomMap.get("mat_pk");
			String matName = bomMap.get("mat_name").toString();
			Material consMat = this.materialRepository.getMaterialById(consumeMatPk);
			String lotUseYn = bomMap.get("lotUseYn").toString();
			float totalQty = 0f;

			if ("Y".equals(lotUseYn)) {
				List<Map<String, Object>> mpiList = this.getMaterialProcessInputList(jr.getId(), consumeMatPk);
				float remainQty = chasuBomQty;
				for (Map<String, Object> mpiMap : mpiList) {
					float reqQty = Float.parseFloat(mpiMap.get("req_qty").toString());
					totalQty += reqQty;
					int matLotId = (int) mpiMap.get("ml_id");
					float currentStock = Float.parseFloat(mpiMap.get("curr_qty").toString());
					if (currentStock == 0) continue;

					MatLotCons mlc = new MatLotCons();
					mlc.setMaterialLotId(matLotId);
					mlc.setOutputDateTime(now);
					mlc.setSourceDataPk(mp.getId());
					mlc.setSourceTableName("mat_produce");
					mlc.set_audit(user);
					mlc.setCurrentStock(currentStock);
					mlc.setSpjangcd(spjangcd);
					if (currentStock >= remainQty) {
						mlc.setOutputQty(reqQty);
						remainQty = 0f;
						this.matLotConsRepository.save(mlc);
						break;
					} else {
						mlc.setOutputQty(reqQty);
						this.matLotConsRepository.save(mlc);
						remainQty -= reqQty;
					}
				}
			} else {
				if ("1".equals(consMat.getUseyn())) {
					result.success = false;
					result.message = "사용 불가능한 품목이 BOM에 등록되어 있습니다.(" + matName + ")";
					return result;
				}
				if (!"0".equals(consMat.getMtyn())) {
					Float cs = consMat.getCurrentStock();
					if (cs == null || cs == 0f) {
						result.success = false;
						result.message = "가용한 품목 재고가 없습니다.(" + matName + ")";
						return result;
					} else if (cs < chasuBomQty) {
						result.success = false;
						result.message = "가용한 품목 재고가 부족합니다.\n(" + matName + ", 필요: " + chasuBomQty + ", 가용: " + cs + ")";
						return result;
					}
				}
				totalQty += chasuBomQty;
			}

			// mat_consu
			MaterialConsume mc = new MaterialConsume();
			mc.setJobResponseId(jr.getId());
			mc.setMaterialId(consumeMatPk);
			mc.setProcessOrder(mp.getProcessOrder());
			mc.setLotIndex(mp.getLotIndex());
			mc.setStartTime(now);
			mc.setEndTime(now);
			mc.setDescription("차수생산분");
			mc.setBomQty(chasuBomQty);
			mc.setConsumedQty(totalQty);
			mc.set_audit(user);
			mc.setState("finished");
			mc.set_status("a");
			mc.setStoreHouseId(consMat.getStoreHouseId());
			mc.setSpjangcd(spjangcd);
			mc = this.matConsuRepository.save(mc);

			// mat_inout out
			MaterialInout mic = new MaterialInout();
			mic.setMaterialId(mc.getMaterialId());
			mic.setStoreHouseId(consMat.getStoreHouseId());
			mic.setLotNumber(mp.getLotNumber());
			mic.setInoutDate(LocalDate.parse(date.format(df)));
			mic.setInoutTime(LocalTime.parse(time.format(tf)));
			mic.setInOut("out");
			mic.setOutputType("consumed_out");
			mic.setOutputQty(totalQty);
			mic.setSourceDataPk(mc.getId());
			mic.setSourceTableName("mat_consu");
			mic.setState("confirmed");
			mic.set_status("a");
			mic.setDescription("차수생산 투입재고 차감");
			mic.set_audit(user);
			mic.setSpjangcd(spjangcd);
			this.matInoutRepository.save(mic);
		}
		return result;
	}

	/** 마지막 공정이면 완성품 입고 (mat_lot + mat_inout in) */
	public void produceInForChasu(Integer mpId, Material m, User user, String spjangcd) {
		MaterialProduce mp = this.matProduceRepository.getMatProduceById(mpId);
		Timestamp now = DateUtil.getNowTimeStamp();
		LocalDate date = LocalDate.now();
		LocalTime time = LocalTime.now();
		DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");

		// mat_lot
		MaterialLot ml = new MaterialLot();
		ml.setLotNumber(mp.getLotNumber());
		ml.setMaterialId(m.getId());
		ml.setInputDateTime(now);
		ml.setInputQty(mp.getGoodQty());
		ml.setCurrentStock(mp.getGoodQty());
		ml.setDescription(mp.getLotIndex() + "차수생산");
		ml.setSourceDataPk(mp.getId());
		ml.setSourceTableName("mat_produce");
		ml.setStoreHouseId(mp.getStoreHouseId());
		ml.set_audit(user);
		ml.setSpjangcd(spjangcd);
		this.matLotRepository.save(ml);

		// mat_inout in
		MaterialInout mip = new MaterialInout();
		mip.setMaterialId(m.getId());
		mip.setStoreHouseId(m.getStoreHouseId());
		mip.setLotNumber(mp.getLotNumber());
		mip.setInoutDate(LocalDate.parse(date.format(df)));
		mip.setInoutTime(LocalTime.parse(time.format(tf)));
		mip.setInOut("in");
		mip.setInputQty(mp.getGoodQty());
		mip.setInputType("produced_in");
		mip.setSourceDataPk(mp.getId());
		mip.setSourceTableName("mat_produce");
		mip.setState("confirmed");
		mip.set_status("a");
		mip.setDescription("차수생산입고");
		mip.set_audit(user);
		mip.setSpjangcd(spjangcd);
		this.matInoutRepository.save(mip);
	}

	/** 작지 양품/불량 합계 갱신 + 지시량 충족 시 자동완료 여부 반환 */
	public boolean recalcJobResAndCheckComplete(Integer jrPk, User user) {
		JobRes jr = this.jobResRepository.getJobResById(jrPk);
		Map<String, Object> sum = this.getJobResponseGoodDefectQty(jrPk);
		float goodSum = sum != null && sum.get("good_qty") != null ? Float.parseFloat(sum.get("good_qty").toString()) : 0f;
		float defectSum = sum != null && sum.get("defect_qty") != null ? Float.parseFloat(sum.get("defect_qty").toString()) : 0f;
		jr.setGoodQty(goodSum);
		jr.setDefectQty(defectSum);

		// 모든 차수 종료 + 양품+불량 >= 지시량 이면 자동완료
		boolean anyUnfinished = this.matProduceRepository.findByJobResponseId(jrPk).stream()
				.anyMatch(mp -> !"finished".equals(mp.getState()));
		float orderQty = jr.getOrderQty() == null ? 0f : jr.getOrderQty().floatValue();
		boolean complete = !anyUnfinished && (goodSum + defectSum) >= orderQty && orderQty > 0;

		if (complete) {
			jr.setState("finished");
			jr.setEndTime(DateUtil.getNowTimeStamp());
		}
		jr.set_audit(user);
		this.jobResRepository.save(jr);
		return complete;
	}
}
