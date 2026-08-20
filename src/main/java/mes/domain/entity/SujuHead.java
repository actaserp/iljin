package mes.domain.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.sql.Date;
import java.sql.Timestamp;

@Entity
@Table(name="suju_head")
@NoArgsConstructor
@Data
@EqualsAndHashCode( callSuper=false)
public class SujuHead extends AbstractAuditModel {

	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	Integer id;
	
	@Column(name="\"JumunDate\"")
	Date JumunDate;

	@Column(name="\"JumunNumber\"")
	String jumunNumber;

	@Column(name="\"TotalPrice\"")
	Double TotalPrice;

	@Column(name="\"ReceivedMoney\"")
	Double ReceivedMoney;

	@Column(name="\"ReceivableMoney\"")
	Double ReceivableMoney;

	@Column(name="\"State\"")
	String State;

	@Column(name="\"ShipmentState\"")
	String ShipmentState;

	@Column(name="\"DeliveryDate\"")
	Date DeliveryDate;

	@Column(name="\"Company_id\"")
	Integer Company_id;

	String spjangcd;

	@Column(name="\"SujuType\"")
	String SujuType;

	@Column(name="\"Description\"")
	String Description;

	@Column(name="\"DeliveryName\"")
	String DeliveryName;

	@Column(name="\"SuJuOrderId\"")
	Integer SuJuOrderId;

	@Column(name="\"SuJuOrderName\"")
	String SuJuOrderName;

	@Column(name="\"EstimateMemo\"")
	String EstimateMemo;
	/** 수주 구분명. 프로젝트 1건에 NQ6 / NQ7 / NQ8 처럼 여러 수주가 붙는다. */
	@Column(name="suju_name")
	String sujuName;

	/** 프로젝트 (tb_da003.projno) */
	@Column(name="project_id")
	String projectId;


}
