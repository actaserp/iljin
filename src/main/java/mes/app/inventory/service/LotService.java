package mes.app.inventory.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.entity.Company;
import mes.domain.entity.JobRes;
import mes.domain.entity.MaterialProduce;
import mes.domain.entity.SeqMaker;
import mes.domain.entity.Shipment;
import mes.domain.entity.ShipmentHead;
import mes.domain.repository.CompanyRepository;
import mes.domain.repository.JobResRepository;
import mes.domain.repository.MatProduceRepository;
import mes.domain.repository.SeqMakerRepository;
import mes.domain.repository.ShipmentHeadRepository;
import mes.domain.repository.ShipmentRepository;
import mes.domain.services.SqlRunner;

@Service
public class LotService {

	@Autowired
	SeqMakerRepository seqMakerRepository;

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MatProduceRepository matProduceRepository;

	@Autowired
	JobResRepository jobResRepository;

	@Autowired
	ShipmentRepository shipmentRepository;

	@Autowired
	ShipmentHeadRepository shipmentHeadRepository;

	@Autowired
	CompanyRepository companyRepository;


	public List<Map<String, Object>> mioLotList(String mioId) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("mioId", mioId);

		String sql = """
            select 
            mi.id as mio_id
            , ml.id as ml_id
            , ml."LotNumber" 
            , m."Name" as "MaterialName"
            , m."Code" as "MaterialCode" 
            , mg."Name" as "MaterialGroupName" 
            , m."MaterialGroup_id" 
            , m."Unit_id" 
            , m."ValidDays" 
            , u."Name" as "UnitName"
            , ml."InputQty"
            , m."Thickness"
            , m."Width"
            , m."Length"
            , to_char(ml."InputDateTime",'yyyy-MM-dd hh24:mi:ss') as "InputDateTime"
            , to_char(ml."EffectiveDate",'yyyy-MM-dd') as "EffectiveDate"
            , ml."Description"
            , ml."StoreHouse_id" as store_house_id
            -- 외작 입고분이면 어느 프로젝트·라인의 지그인지 (라벨에 찍는다). 일반 구매품은 NULL
            , s.project_id as "ProjNo"
            , s.line as "LineName"
            from mat_lot ml  
                left join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id" 
                left join unit u on u.id = m."Unit_id" 
                left join mat_inout mi on ml."SourceDataPk" = mi.id and ml."SourceTableName" ='mat_inout'
                left join balju b on mi."SourceTableName" = 'balju' and b.id = mi."SourceDataPk"
                left join suju s on b."PlanTableName" = 'suju' and s.id = b."PlanDataPk"
            where mi.id = cast(:mioId as Integer) 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, param);
		return items;
	}

	/**
	 * 외작 입고 → LOT 자동 부여.
	 *
	 * 예전에는 수불전표등록에서 [로트 입고 등록] → [저장] 을 눌러야 LOT 이 생겼다.
	 * 외작 지그는 출하 때 LOT 라벨을 스캔해야 하므로 <b>입고 저장과 동시에</b> 만든다.
	 *
	 *  - 지그 1대 = LOT 1건 (InputQty 1). 2대가 들어오면 LOT 2건 → 라벨 2장.
	 *    대수가 비정상적으로 크면(MAX_SPLIT 초과) 쪼개지 않고 1건으로 만든다 — 입력 실수 대비.
	 *  - 확정(confirmed) 입고만. 입고검사 대기(가입고)는 확정될 때 수불전표등록에서 등록한다.
	 *  - 이미 LOT 이 있으면 만들지 않는다 (재저장·중복 호출 안전).
	 *  - 유효일시는 일진에 의미가 없어 2999-12-31.
	 *  - 그 품목의 로트관리(LotUseYN)를 켠다. 꺼져 있으면 LOT 선택 팝업·LOT 재고조정에 안 보인다.
	 *
	 * 컬럼·값은 MaterialInoutController.lot_save 와 같다 (SourceTableName='mat_inout').
	 *
	 * @return 만든 LOT 번호 목록 (없으면 빈 목록)
	 */
	public List<String> autoLotsForReceipt(Integer mioId, Integer userId) {
		final int MAX_SPLIT = 50;
		List<String> made = new java.util.ArrayList<>();
		if (mioId == null) return made;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("mioId", mioId);

		Map<String, Object> mi = this.sqlRunner.getRow("""
			SELECT mi.id, mi.spjangcd, mi."Material_id" AS material_id, mi."StoreHouse_id" AS store_house_id
			     , COALESCE(mi."InputQty", 0) AS qty, mi."State" AS state
			     , (SELECT COUNT(*) FROM mat_lot ml
			         WHERE ml."SourceTableName" = 'mat_inout' AND ml."SourceDataPk" = mi.id) AS lot_cnt
			FROM mat_inout mi
			WHERE mi.id = :mioId
			""", p);
		if (mi == null) return made;

		double qty = Double.parseDouble(String.valueOf(mi.get("qty")));
		long lotCnt = Long.parseLong(String.valueOf(mi.get("lot_cnt")));
		if (!"confirmed".equals(String.valueOf(mi.get("state"))) || qty <= 0 || lotCnt > 0
				|| mi.get("material_id") == null || mi.get("store_house_id") == null) {
			return made;
		}

		// 1대씩. 소수(1.5 등)는 나머지를 마지막 LOT 에 싣는다
		List<Double> sizes = new java.util.ArrayList<>();
		if (qty > MAX_SPLIT) {
			sizes.add(qty);
		} else {
			int whole = (int) Math.floor(qty);
			for (int i = 0; i < whole; i++) sizes.add(1d);
			double rest = qty - whole;
			if (rest > 0) {
				if (sizes.isEmpty()) sizes.add(rest);
				else sizes.set(sizes.size() - 1, 1d + rest);
			}
		}

		for (Double size : sizes) {
			String lot = this.make_lot_in_number();
			MapSqlParameterSource ip = new MapSqlParameterSource();
			ip.addValue("spjangcd", mi.get("spjangcd"));
			ip.addValue("lot", lot);
			ip.addValue("materialId", mi.get("material_id"));
			ip.addValue("qty", size);
			ip.addValue("mioId", mioId);
			ip.addValue("storeHouseId", mi.get("store_house_id"));
			ip.addValue("userId", userId);
			this.sqlRunner.execute("""
				INSERT INTO mat_lot (
				     spjangcd, "LotNumber", "Material_id", "InputDateTime"
				   , "InputQty", "CurrentStock", "OutQtySum", "EffectiveDate", "Description"
				   , "SourceTableName", "SourceDataPk", "StoreHouse_id"
				   , _status, _created, _creater_id
				) VALUES (
				     :spjangcd, :lot, :materialId, now()
				   , :qty, :qty, 0, TIMESTAMP '2999-12-31 00:00:00', '외작 입고 자동 LOT'
				   , 'mat_inout', :mioId, :storeHouseId
				   , 'a', now(), :userId
				)
				""", ip);
			made.add(lot);
		}

		MapSqlParameterSource mp = new MapSqlParameterSource();
		mp.addValue("mid", mi.get("material_id"));
		this.sqlRunner.execute("""
			UPDATE material SET "LotUseYN" = 'Y'
			WHERE id = :mid AND COALESCE("LotUseYN", 'N') <> 'Y'
			""", mp);

		return made;
	}

	// Lot 번호 만들기
	public String make_lot_in_number() {

		// 현재 날,시간
		Timestamp today = new Timestamp(System.currentTimeMillis());

		// 현재 일자
		LocalDate date = LocalDate.now();
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd");

		List<SeqMaker> sm = this.seqMakerRepository.findByCodeAndBaseDate("LOT_IN",date.format(dateFormat));

		SeqMaker s = new SeqMaker();

		if (sm.size() > 0) {
			s = sm.get(0);
		} else {
			s.setCode("LOT_IN");
			s.setBaseDate(date.format(dateFormat));
			s.setCurrVal(0);
			s.set_modified(today);
		}
		s.setCurrVal(s.getCurrVal() + 1);
		this.seqMakerRepository.save(s);

		String lotNumber = "LI-" + date.format(dateFormat) + "-" +String.format("%04d", s.getCurrVal());

		return lotNumber;
	}

	// Lot 번호 만들기
	public String make_production_lot_in_number(String type) {

		// 현재 날,시간
		Timestamp today = new Timestamp(System.currentTimeMillis());

		// 현재 일자
		LocalDate date = LocalDate.now();
		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd");

		List<SeqMaker> sm = this.seqMakerRepository.findByCodeAndBaseDate("PROD_LOT_IN",date.format(dateFormat));

		SeqMaker s = new SeqMaker();

		if (sm.size() > 0) {
			s = sm.get(0);
		} else {
			s.setCode("PROD_LOT_IN");
			s.setBaseDate(date.format(dateFormat));
			s.setCurrVal(0);
			s.set_modified(today);
		}
		s.setCurrVal(s.getCurrVal() + 1);
		this.seqMakerRepository.save(s);

		String lotNumber = type + "-" + date.format(dateFormat) + "-" +String.format("%04d", s.getCurrVal());

		return lotNumber;
	}

	public List<Map<String, Object>> lotDetail(String lotNumber) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
		        select
			      ml.id as ml_id
		        , ml."LotNumber"
		        , ml."InputQty" 
		        , ml."CurrentStock" 
		        , m."Name" as mat_name
		        , mg."Name" as mat_group_name
		        , mg."MaterialType" as mat_type
                , to_char(ml."InputDateTime", 'yyyy-mm-dd hh24:mi') as "InputDateTime" 
	            , to_char(ml."EffectiveDate" , 'yyyy-mm-dd hh24:mi') as "EffectiveDate"
	            , fn_code_name('mat_type', mg."MaterialType" ) as mat_type_name
                , u."Name" as unit_name
		        , ml."SourceTableName" 
		        , ml."SourceDataPk" 
		        from mat_lot ml
		        inner join material m on m.id = ml."Material_id"
		        left join mat_grp mg on mg.id = m."MaterialGroup_id" 
		        left join unit u on u.id = m."Unit_id" 
		        where ml."LotNumber" = :lotNumber
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getMaterialTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
with recursive T as (    
         with P as(
            select 
            jr.id as jr_id
            , jr."WorkOrderNumber" 
            , mp.id as mp_id
            , ''::text as p_lot_number
            , mp."LotNumber" as lot_number
            , jr."Material_id" as mat_pk
            , ml."EffectiveDate"
            from job_res jr
            inner join mat_produce mp on mp."JobResponse_id" = jr.id            
            inner join material m on m.id=jr."Material_id" 
            left join mat_grp mg on mg.id = m."MaterialGroup_id" 
            inner join mat_lot ml on ml."SourceDataPk" =mp.id and ml."SourceTableName" ='mat_produce'
            where ml."LotNumber" = :lotNumber
         ) 
         ,A as(
          select 
           jr.jr_id
          , jr."WorkOrderNumber" 
          , mp."LotNumber" p_lot_number
          , mc."Material_id" as mat_pk
          , mp.id as mp_id
          , mc.id as mc_id
          , mc."BomQty"
          , mc."ConsumedQty" 
          from p as jr
          left join mat_consu mc on mc."JobResponse_id" = jr.jr_id
          left join mat_produce mp on mp."JobResponse_id" =jr.jr_id
        ) , B as(
        select 
        A.*
        ,ml."LotNumber" as lot_number
        , ml."Material_id"  
        , ml."EffectiveDate"
        from mat_lot ml 
        inner join mat_lot_cons mlc on mlc."MaterialLot_id" =ml.id
        inner join A on A.mp_id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce' and ml."Material_id" =A.mat_pk  
        )        
        select 
        jr_id
        , "WorkOrderNumber" 
        ,p_lot_number
        , lot_number
        , null::integer as mp_id
        , null::integer as p_mat_pk        
        , mat_pk 
        , 1 as lvl 
        , "EffectiveDate"
        from P
        union all 
        select 
        B.jr_id
        , B."WorkOrderNumber" 
        , T.lot_number as p_lot_number
        , B.lot_number 
        , B.mp_id
        , T.mat_pk as p_mat_pk
        , B.mat_pk as mat_pk
        , t.lvl +1 as lvl
        , B."EffectiveDate"
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
        )        
        select 
        jr_id 
        , "WorkOrderNumber" 
        , p_mat_pk
        , mat_pk
        , p_lot_number
        , m1."Name" as p_mat_name
        , lot_number
        , m2."Name" as mat_name
        , (
            select 
            sum(mlc."OutputQty")
            from mat_lot ml 
            inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id 
            where T.mat_pk=ml."Material_id" and T.mp_id=mlc."SourceDataPk" and mlc."SourceTableName"='mat_produce'
            group by ml."Material_id" 
            ) as lot_consume_qty
        , T.mp_id
        , T.lvl
        , u."Name" as unit_name
        from T
        left join material m1 on m1.id = T.p_mat_pk
        inner join material m2 on m2.id = T.mat_pk
        left join unit u on u.id = m2."Unit_id"
        order by lvl
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getProductTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
		  with recursive T as(
            select 
            null::text as p_lot_number
            , ml."LotNumber" as lot_number  
            , null::float as mp_id
            , null::text as wo
            , null::int as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , 1 as lvl
            from mat_lot ml        
            where ml."LotNumber" = :lotNumber
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            ,mp.id as mp_id
            , jr."WorkOrderNumber" as wo
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            , (t.lvl+1 ) as lvl
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            inner join T on T.lot_number = ml."LotNumber" 
            inner join job_res jr on jr.id=mp."JobResponse_id" 
        )
          select 
          concat(p_lot_number , lot_number) as id
          , p_lot_number 
          , lot_number
          , m2."Name" as p_mat_name
          , m."Name" as mat_name
          , p_mat_pk
          , l_mat_pk
          , wo      
          , lvl
          ,(select sum(mlc."OutputQty") from mat_lot ml 
            inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id
            where T.l_mat_pk=ml."Material_id" and T.mp_id=mlc."SourceDataPk" and mlc."SourceTableName"='mat_produce'
            group by ml."Material_id") as lot_consume_qty
          , u."Name" as unit_name
          from T
          left join material m on m.id = l_mat_pk
          left join material m2 on m2.id = p_mat_pk
          left join unit u on u.id=m."Unit_id"
          order by lvl
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getMaterialInoutTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);

		String sql = """
	  with recursive T as (
         with P as(
            select 
            mp.id as mp_id
            , ''::text as p_lot_number
            , mp."LotNumber" as lot_number
            , jr."Material_id" as mat_pk
            from job_res jr
            left join mat_produce mp on mp."JobResponse_id" = jr.id 
            inner join mat_lot ml on ml."LotNumber" =mp."LotNumber" 
            where mp."LotNumber" = :lotNumber
         ) 
         ,A as(
          select 
          mp."LotNumber" p_lot_number
          , mc."Material_id" as mat_pk
          , mp.id as mp_id
          from job_res jr
          left join mat_consu mc on mc."JobResponse_id" = jr.id
          left join mat_produce mp on mp."JobResponse_id" =jr.id
        ), B as(
        select 
        A.*
        ,ml."LotNumber" as lot_number
        , ml."Material_id"
        from mat_lot ml 
        inner join mat_lot_cons mlc on mlc."MaterialLot_id" =ml.id 
        inner join A on A.mp_id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce' and ml."Material_id" =A.mat_pk 
        )
        select  
        p_lot_number, lot_number, null::integer as mp_id, mat_pk  
        from P
        union all 
        select 
        T.lot_number as p_lot_number, B.lot_number, B.mp_id, B.mat_pk as mat_pk
        from T   
          inner join B on B.p_lot_number  = T.lot_number 
        ), LL as
        ( 
        -- (1) 생산품 LOT 에서 역추적된 재료 LOT 들
        select T.lot_number from T 
        inner join material m2 on m2.id = T.mat_pk 
        left join mat_grp mg on mg.id = m2."MaterialGroup_id" 
        group by T.lot_number
        union
        -- (2) 조회한 LOT 자체가 입고(발주입고/미발주입고)로 생성된 원재료 LOT 인 경우
        --     생산 이력이 없어도 자기 입고정보는 보여야 한다
        select ml."LotNumber" as lot_number
        from mat_lot ml
        where ml."LotNumber" = :lotNumber
          and ml."SourceTableName" = 'mat_inout'
        )
        select 
        LL.lot_number
        , to_char(ml."EffectiveDate" ,'yyyy-MM-dd hh24:mi:ss') as "EffectiveDate"
        , to_char(mi."InoutDate",'yyyy-MM-dd') as "InoutDate"
        , mi."InoutTime"
        , mi."InputQty"
        , fn_code_name('input_type',mi."InputType")as input_type_name
        , c."Name" as company_name
        , m."Name" as mat_name
        from LL 
        left outer join mat_lot ml on ml."LotNumber" =ll.lot_number and ml."SourceTableName" ='mat_inout'
        left join material m on m.id = ml."Material_id" 
        left join mat_inout mi on mi.id = ml."SourceDataPk"
        left join company c on c.id = mi."Company_id"
        order by m."Name", lot_number
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}

	public List<Map<String, Object>> getProductShipmentTracking(String lotNumber) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("lotNumber", lotNumber);
		String sql = """
		 with recursive T as(
            select 
            null::text as p_lot_number
            , ml."LotNumber" as lot_number  
            , null::float as mp_id
            , null::int as p_mat_pk
            , ml."Material_id" as l_mat_pk
            from mat_lot ml
            where ml."LotNumber"= :lotNumber
            union all 
            select 
             ml."LotNumber" as p_lot_numbe
            ,mp."LotNumber" as lot_number
            , mp.id as mp_id
            , mp."Material_id" as p_mat_pk
            , ml."Material_id" as l_mat_pk
            from mat_lot ml 
            inner join mat_lot_cons mlc ON mlc."MaterialLot_id" =ml.id 
            left join mat_produce mp on mp.id = mlc."SourceDataPk" and mlc."SourceTableName" ='mat_produce'
            inner join T on T.lot_number = ml."LotNumber" 
            inner join job_res jr on jr.id=mp."JobResponse_id" 
	        ), pp as ( select lot_number from T group by lot_number)
	        select 
	        pp.lot_number 
	        , m."Name" as mat_name
	        , sh."Company_id" 
	        , c."Name" as company_name
	        , sh."ShipDate" 
	        , s."Qty" 
	        , fn_code_name('shipment_state', sh."State" ) as shipment_state
	        from pp 
	        inner join mat_lot ml on ml."LotNumber" =lot_number
	        inner join material m on m.id = ml."Material_id" 
	        inner join mat_lot_cons mlc on mlc."MaterialLot_id"=ml.id and mlc."SourceTableName" ='shipment'
	        inner join shipment s on s.id=mlc."SourceDataPk" 
	        inner join shipment_head sh on sh.id = s."ShipmentHead_id" 
	        left join company c on c.id = sh."Company_id"
				""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
		return items;
	}




















	public List<Map<String, Object>> getMatLotList(String mat_type, Integer mat_group, Integer material, String lot_num, String date_from, String date_to, String cond) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("mat_type", mat_type);
		dicParam.addValue("mat_group", mat_group);
		dicParam.addValue("material", material);
		dicParam.addValue("lot_num", lot_num);

		if (StringUtils.isEmpty(date_from) == false && StringUtils.isEmpty(date_to) == false) {
			dicParam.addValue("date_from", Timestamp.valueOf(date_from + " 00:00:00"));
			dicParam.addValue("date_to", Timestamp.valueOf(date_to + " 23:59:59"));
		}

		String sql = """
        		select ml.id
                , to_char(ml."InputDateTime", 'yyyy-mm-dd hh24:mi') as prod_date
                , ml."LotNumber" as lot_num
                , fn_code_name('mat_type', mg."MaterialType" ) as mat_type
                , mg."Name" as mat_group
                , m."Code" as mat_code
                , m."Name" as mat_name
                , ml."InputQty" as input_qty
                , ml."OutQtySum" as out_qty
                , ml."CurrentStock" as current_stock
                , ml."Description" as description
                , ml."SourceDataPk" as source_id
                , ml."SourceTableName" as source_table
                from mat_lot ml 
                inner join material m on m.id = ml."Material_id"
                left join mat_grp mg on mg.id = m."MaterialGroup_id"
                where 1=1
            """;

		if (StringUtils.isEmpty(mat_type)==false) {
			sql += " and mg.\"MaterialType\" = :mat_type ";
		}

		if (mat_group != null) {
			sql += " and mg.id = :mat_group ";
		}

		if (material != null) {
			sql += " and m.id = :material ";
		}

		if (StringUtils.isEmpty(lot_num) == false) {
			sql += " and ml.\"LotNumber\" ilike concat('%%',:lot_num,'%%') ";
		}

		if (StringUtils.isEmpty(date_from) == false && StringUtils.isEmpty(date_to) == false) {
			sql += " and ml.\"InputDateTime\" between :date_from and :date_to ";
		}

		if ("remain".equals(cond)) {
			sql += " and ml.\"CurrentStock\" > 0 ";
		}

		sql += " order by prod_date desc ";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		for (int i = 0; i < items.size(); i++) {

			if ("mat_produce".equals((String) items.get(i).get("source_table"))) {

				Integer source_id = (Integer) items.get(i).get("source_id");
				MaterialProduce mpList = this.matProduceRepository.getMatProduceById(source_id);

				if (mpList != null) {

					Integer jobres_id = mpList.getJobResponseId();
					JobRes jr = this.jobResRepository.getJobResById(jobres_id);

					if (jr != null) {

						String work_order_num = jr.getWorkOrderNumber();

						if (work_order_num != null) {
							items.get(i).put("reg_history", "생산 (작지번호: " + work_order_num + ")");
						}
					}
				}
			}
		}

		return items;
	}

	// LOT 소비내역 조회
	public List<Map<String, Object>> getConsumedList(Integer matlot_id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("matlot_id", matlot_id);

		String sql = """
        		select mlc.id 
                , ml."LotNumber" as lot_num
                , to_char(mlc."OutputDateTime", 'yyyy-mm-dd hh24:mi') as consumed_date
                , mlc."OutputQty" as consumed_qty
                , mlc."Description" as description
                , mlc."SourceDataPk" as source_id
                , mlc."SourceTableName" as source_table
                from mat_lot_cons mlc 
                inner join mat_lot ml on ml.id = mlc."MaterialLot_id"
                where mlc."MaterialLot_id" = :matlot_id
            """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		for (int i = 0; i < items.size(); i++) {

			if ("shipment".equals((String) items.get(i).get("source_table"))) {

				Integer source_id = (Integer) items.get(i).get("source_id");
				Shipment spList = this.shipmentRepository.getShipmentById(source_id);

				if (spList != null) {
					Integer shipment_head_id = spList.getShipmentHeadId();

					if (shipment_head_id != null) {
						ShipmentHead sh =  this.shipmentHeadRepository.getShipmentHeadById(shipment_head_id);

						if (sh != null) {
							Integer company_id = sh.getCompanyId();

							if (company_id != null) {
								Company company = this.companyRepository.getCompanyById(company_id);

								if (company != null) {
									String company_name = company.getName();

									items.get(i).put("consumed_history", "출하 (고객사: " + company_name + ")");
								}
							}
						}
					}
				}
			}
		}

		return items;
	}


}