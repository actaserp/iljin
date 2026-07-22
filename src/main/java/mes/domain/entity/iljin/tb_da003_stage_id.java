package mes.domain.entity.iljin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class tb_da003_stage_id implements Serializable {

	@Column(name = "spjangcd")
	private String spjangcd;  // 사업장코드

	@Column(name = "projno")
	private String projno;    // 프로젝트번호

	@Column(name = "seq")
	private int seq;          // 순번
}