package mes.app.shipment;

import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;

import mes.app.util.UtilClass;
import mes.domain.entity.*;
import mes.domain.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.shipment.service.ShipmentOrderService;
import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;

@RestController
@RequestMapping("/api/shipment/shipment_order")
public class ShipmentOrderController {

	@Autowired 
	private ShipmentOrderService shipmentOrderService;
	
	@Autowired
	ShipmentRepository shipmentRepository;
	
	@Autowired
	ShipmentHeadRepository shipmentHeadRepository;
	
	@Autowired
	RelationDataRepository relationDataRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	MaterialRepository materialRepository;
	@Autowired
	private MatLotConsRepository matLotConsRepository;


	@GetMapping("/suju_list")
	public AjaxResult getSujuList(
			@RequestParam("srchStartDt") String dateFrom,
			@RequestParam("srchEndDt") String dateTo,
			@RequestParam("not_ship") String notShip,
//			@RequestParam("cboCompany") String compPk,
			@RequestParam("cboMatGroup") String matGrpPk,
			@RequestParam("cboMaterial") String matPk,
			@RequestParam("keyword") String keyword,
			@RequestParam(value = "CompanySearch", required = false) String company
			){

		if(dateFrom.isEmpty()){
			//dateFrom = UtilClass.getDayByParamAdd(0); // 오늘날짜
		}

		if(dateTo.isEmpty()){
			//dateTo = UtilClass.getDayByParamAdd(7);
		}

		List<Map<String, Object>> items = this.shipmentOrderService.getSujuList(dateFrom,dateTo,notShip,matGrpPk,matPk,keyword, company);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/product_list")
	public AjaxResult getProductList(
			@RequestParam("cboMatGroup") String matGrpPk,
			@RequestParam("cboMaterial") String matPk,
			@RequestParam("keyword") String keyword){
		
		List<Map<String, Object>> items = this.shipmentOrderService.getProductList(matGrpPk,matPk,keyword);
		
		AjaxResult result = new AjaxResult();
		result.data = items;
		
		return result;
	}

	//모달반영도 여기바라봄.
	@PostMapping("/save_shipment_order")
	@Transactional
	public AjaxResult saveShipmentOrder(
			@RequestParam("Company_id") Integer CompanyId,
			@RequestParam("Description") String Description,
			@RequestParam("DeliveryName") String DeliveryName,
			@RequestParam("ShipDate") String Ship_date,
			@RequestBody MultiValueMap<String,Object> Q,
			@RequestParam("TableName") String TableName,
			HttpServletRequest request,
			Authentication auth) {
		
		User user = (User)auth.getPrincipal();
		
		AjaxResult result = new AjaxResult();

		Timestamp today = new Timestamp(System.currentTimeMillis());  //shipment_head의 OrderDate 컬럼값 yyyy-MM-dd
		Timestamp shipDate = CommonUtil.tryTimestamp(Ship_date); //shipment_head의 ShipDate 컬럼값 yyyy-MM-dd
		
		List<Map<String, Object>> data = CommonUtil.loadJsonListMap(Q.getFirst("Q").toString());
		ShipmentHead smh = new ShipmentHead();

		List<Suju> relationSujuList = shipmentOrderService.getRelationSujuList(data);

		System.out.println(relationSujuList);
		smh.setCompanyId(CompanyId);
		smh.setShipDate(shipDate);
		smh.setOrderDate(today);
		smh.setDescription(Description);
		smh.setDeliveryName(DeliveryName);
		smh.set_audit(user);
		smh.setState("ordered");
		

		double orderSum = 0;
		double totalPrice = 0;
		double totalVat = 0;


		// 1. 출하에 포함된 mat_id 추출
		Set<Integer> matIds = data.stream()
				.map(d -> (Integer) d.get("mat_id"))
				.collect(Collectors.toSet());

		//수주가 아닌 품목대상일때 해당 품목에 대한 단가 정보를 가져와야 해서
		Set<Integer> product_materialId = data.stream()
				.filter(s -> {
					Object sujuPk = s.get("suju_pk");
					return sujuPk == null || sujuPk.toString().trim().isEmpty();
				})
				.map(s -> (Integer) s.get("mat_id"))
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());

		List<Map<String, Object>> productItems = new LinkedList<>();

		if(product_materialId.size() > 0){
			productItems = shipmentOrderService.getProdcutList(product_materialId, CompanyId);
		}

		// 2. 해당 품목의 현재고 조회
		List<Material> materialList = materialRepository.findByIdIn(matIds);



		Map<Integer, Material> materialMap = materialList.stream()
				.collect(Collectors.toMap(Material::getId, Function.identity()));




		//출하하려는 항목의 누적 클래스, 여기서만 사용하는 클래스라서 이너클래스로 생성함
		class MatSummary {
			String name;
			double totalQty;

			public MatSummary(String name, double totalQty){
				this.name = name;
				this.totalQty = totalQty;
			}
			public void addQty(double qty){
				this.totalQty += qty;
			}
		}

		//출하하려는 목록
		Map<Integer, MatSummary> GroupedMaterial = new HashMap<>();

		for(Map<String, Object> item : data){
			Integer matId = (Integer) item.get("mat_id");
			String matName = item.get("mat_name").toString();

			double orderQty = ((Number) item.get("order_qty")).doubleValue();
			//double orderQty = (double) item.get("order_qty");

			GroupedMaterial.compute(matId, (id, summary) -> {
				if(summary == null){
					return new MatSummary(matName, orderQty);
				}else{
					summary.addQty(orderQty);
					return summary;
				}
			});
		}



		//현재고보다 많은 출하를 하려하면 빠꾸
		for(Map.Entry<Integer, Material> entry : materialMap.entrySet()){
			Integer matid = entry.getKey();
			Material material = entry.getValue();

			MatSummary summary = GroupedMaterial.get(matid);

			if(summary != null){
				double totalQty = summary.totalQty; // 출하하려는 재고
				Float currentStock = material.getCurrentStock();
				String mtyn = material.getMtyn(); // 재고관리 여부 컬럼 (1/0)
				// 품목정보의 재고관리 false('0')일 경우 재고 신경쓰지 않고 출하 가능하도록
				if (!"0".equalsIgnoreCase(mtyn)) {
					// 재고관리 'Y'인 경우에만 재고 확인
//					if (currentStock == null) {
//						result.success = false;
//						result.message = "품목 [" + material.getName() + "]의 재고 현황이 존재하지 않습니다.";
//						return result;
//					}
//
//					if (Math.round(totalQty * 1000) / 1000.0 > Math.round(currentStock * 1000) / 1000.0) {
//						result.success = false;
//						result.message = "품목 [" + material.getName() + "]의 출하 수량이 현재고를 초과합니다.";
//						return result;
//					}
				}
			}
		}
		smh = this.shipmentHeadRepository.save(smh);


		for(int i = 0; i < data.size(); i++) {

			//int orderQty = Integer.parseInt(data.get(i).get("order_qty").toString());

			double orderQty = Double.parseDouble(data.get(i).get("order_qty").toString());

			if (orderQty <=  0) {
				continue;
			}


			Shipment sm = new Shipment();
			int mat_id = (int) data.get(i).get("mat_id");
			sm.setShipmentHeadId(smh.getId());
			sm.setMaterialId(mat_id);
			sm.setOrderQty((double)orderQty);
			sm.setQty((double) 0);
			if (data.get(i).get("description") != null) {
			sm.setDescription((String)data.get(i).get("description"));
			}

			Object sujuPkObj = data.get(i).get("suju_pk");
			Integer suJuPkParsedInt = sujuPkObj == "" ? null : (int) sujuPkObj;
			sm.setSourceDataPk(suJuPkParsedInt);

			if(TableName.equals("product")) {
				sm.setSourceTableName(TableName);

				if(productItems != null && !productItems.isEmpty()){

					Map<String, Object> productList = productItems.stream()
							.filter(s -> {
								Object materialId = s.get("Material_id");
								return materialId != null && materialId.equals(mat_id);
							})
									.findFirst().orElse(null);

					Double unitPrice = null;

					if(productList != null){
						 unitPrice = ((Double) productList.get("UnitPrice"));

						sm.setUnitPrice(unitPrice);
						sm.setPrice(unitPrice * orderQty);
						sm.setVat((unitPrice * orderQty) * 0.1);
						totalPrice += unitPrice * orderQty;
						totalVat += (unitPrice * orderQty) * 0.1;
					}else{
						sm.setUnitPrice(null);
						sm.setPrice(null);
						totalVat += 0;
						totalPrice += 0;
					}

				}else{
					sm.setUnitPrice(null);
					sm.setPrice(null);
					sm.setVat(null);
					totalVat += 0;
					totalPrice += 0;
				}

			} else if (TableName.equals("suju")) {


				Suju item = relationSujuList.stream()
						.filter(s -> Objects.equals(s.getId(), suJuPkParsedInt))
						.findFirst()
						.orElse(null);

				if (item != null) {
					double qty = item.getSujuQty();

					if (item.getPrice() != null) {

						double UnitPrice = item.getUnitPrice(); //단가
						double Standard = UtilClass.parseStandard(item.getStandard());

						String invatyn = item.getInVatYN(); //부가세 포함 여부
						sm.setUnitPrice(UnitPrice);

						double price;
						if (invatyn.equals("Y")) {
							price = UnitPrice / 1.1; // 공급가 단가
						} else {
							price = UnitPrice;       // 이미 공급가 단가임
						}

						double PriceSum = price * orderQty * Standard;
						double vat = PriceSum * 0.1;

						// 반올림 (소수점 셋째자리에서)
						PriceSum = Math.round(PriceSum * 100.0) / 100.0;
						vat = Math.round(vat * 100.0) / 100.0;

						sm.setPrice(PriceSum);
						sm.setVat(vat);
						totalPrice += PriceSum;
						totalVat += vat;

					}
					sm.setSourceTableName("rela_data");
				}
			}
			sm.set_audit(user);
			sm = this.shipmentRepository.save(sm);

			if(TableName.equals("suju")){

				RelationData rd = new RelationData();
				Integer sujuPk = (Integer)data.get(i).get("suju_pk");
				rd.setTableName1("suju");
				rd.setDataPk1(sujuPk);
				rd.setTableName2("shipment");
				rd.setDataPk2(sm.getId());
				rd.set_audit(user);
				rd.setRelationName("");
				rd.setNumber1(orderQty);
				rd = this.relationDataRepository.save(rd);

			}
			orderSum += orderQty;
		}

		smh.setTotalQty((double)orderSum);
		smh.setTotalPrice(totalPrice);
		smh.setTotalVat(totalVat);

		smh = this.shipmentHeadRepository.save(smh);
		
		result.data = smh;
		
		return result;
	}
		

	// 출하지시 목록 조회
	@GetMapping("/order_list")
	public AjaxResult getShipmentOrderList(
			@RequestParam(value="srchStartDt", required=false) String date_from, 
			@RequestParam(value="srchEndDt", required=false) String date_to,
			@RequestParam(value="chkNotShipped", required=false) String not_ship, 
			@RequestParam(value="cboCompany", required=false) Integer comp_pk,
			@RequestParam(value="cboMatGroup", required=false) Integer mat_grp_pk, 
			@RequestParam(value="cboMaterial", required=false) Integer mat_pk,
			@RequestParam(value="CompanySearch", required=false) String company,
			@RequestParam(value="keyword", required=false) String keyword,
			HttpServletRequest request) {
			
		String state = "";
		if("Y".equals(not_ship)) {
			state= "ordered";
		} else {
			state = "";
		}
		
		List<Map<String, Object>> items = this.shipmentOrderService.getShipmentOrderList(date_from, date_to, state, comp_pk, mat_grp_pk, mat_pk, keyword, company);
        AjaxResult result = new AjaxResult();
        result.data = items;
		return result;
	}
	
	// 출하 품목 목록 조회
	@GetMapping("/shipment_item_list")
	public AjaxResult getShipmentItemList(
			@RequestParam(value="head_id", required=false) Integer head_id,
			HttpServletRequest request) {
		List<Map<String, Object>> items = this.shipmentOrderService.getShipmentItemList(head_id);
        AjaxResult result = new AjaxResult();
        result.data = items;
		return result;
	}
	
	// 출하일 변경
	@PostMapping("/update_ship_date")
	public AjaxResult updateShipDate(
			@RequestParam(value="head_id", required=false) Integer head_id,
			@RequestParam(value="ship_date", required=false) String ship_date,
			HttpServletRequest request,
			Authentication auth) {
		
        AjaxResult result = new AjaxResult();
		User user = (User)auth.getPrincipal();
		
		ShipmentHead shipmentHead = this.shipmentHeadRepository.getShipmentHeadById(head_id);

		if ("shipped".equals(shipmentHead.getState())) {		//if (shipmentHead.getState().equals("shipped")) {
			result.success = false;
		} else {
			shipmentHead.setShipDate(CommonUtil.tryTimestamp(ship_date));
			shipmentHead.set_audit(user);
			
			shipmentHead = this.shipmentHeadRepository.save(shipmentHead);
			
			result.data = shipmentHead;
		}
		
		return result;
	}
	
	// 출하지시 취소
	@PostMapping("/cancel_order")
	public AjaxResult cancelOrder(
			@RequestParam(value="shipmenthead_id", required=false) Integer head_id,
			HttpServletRequest request,
			Authentication auth) {
		
        AjaxResult result = new AjaxResult();
		
		ShipmentHead head = this.shipmentHeadRepository.getShipmentHeadById(head_id);

		if ("shipped".equals(head.getState())) {
			result.success = false;
		} else {

			List<Shipment> ShipmentList = this.shipmentRepository.findByShipmentHeadId(head_id);

			List<Integer> shipment_ids = ShipmentList.stream()
					.map(Shipment::getId)
					.toList();

			List<MatLotCons> byShipmentIds = matLotConsRepository.findByShipmentIds(shipment_ids);

			if(!byShipmentIds.isEmpty()){
				result.success = false;
				result.message = "할당된 LOT가 있는 출하상세가 존재합니다.";
				return result;
			}

			this.transactionTemplate.executeWithoutResult(status->{			
				try {

					// 출하지시 취소 시 수주-출하 연결데이터(rela_data)도 함께 정리
					// 이걸 지우지 않으면 출하등록 화면에서 지시수량이 남은 것으로 오인되어
					// 해당 수주가 '미출하만 보기' 목록에서 빠짐
					if (!shipment_ids.isEmpty()) {
						this.relationDataRepository.deleteByTableName2AndDataPk2In("shipment", shipment_ids);
					}

					this.shipmentRepository.deleteByShipmentHeadId(head_id);
					this.shipmentHeadRepository.deleteById(head_id);
				}
				catch(Exception ex) {
					TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
					result.success=false;
					result.message = ex.toString();
				}				
			});					
		}
		return result;
	}
}
