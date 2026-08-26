package mes.domain.entity.iljin;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "tb_da003_stage")
@NoArgsConstructor
@Data
public class tb_da003_stage {

	@EmbeddedId
	private tb_da003_stage_id id;   // 복합키 (spjangcd + projno + seq)

	@Column(name = "stagenm")
	private String stagenm;   // 진행단계명

	@Column(name = "pldate")
	private String pldate;    // 예정일 (yyyymmdd)

	@Column(name = "cpdate")
	private String cpdate;    // 완료일 (yyyymmdd)

	@Column(name = "endflag")
	private String endflag;   // 완료여부 (0:진행중, 1:완료)

	@Column(name = "remark")
	private String remark;    // 비고

	@Column(name = "indate")
	private String indate;    // 등록일 (yyyymmdd)

	@Column(name = "inuserid")
	private String inuserid;  // 등록자 ID

	@Column(name = "wbs_plan_id")
	private Integer wbsPlanId;

	@Column(name = "wbs_relink")
	private String wbsRelink;

	@Column(name = "charge_id")
	private Integer chargeId;
}