package mes.app.sales.service;

import java.util.regex.Pattern;

/**
 * JIG SET 문자열 → 생산 수량 환산.
 *
 * <pre>
 *   "1/1" → 2      "2/2" → 4      "2" → 2      "1" → 1
 * </pre>
 *
 * <p>구분자 {@code '/'} 로 잘라 <b>합산</b>한다. 곱이 아니다 ("1/1" 이 2 이므로).
 * 좌/우 한 쌍인 지그를 {@code "1/1"} 로 적는 현장 표기에서 온 규칙이다.
 *
 * <p><b>원문은 반드시 {@code suju."Standard"} 에 그대로 보존한다.</b>
 * {@code "SujuQty"} 는 이 파서가 만든 파생값이므로, 규칙이 바뀌거나 표기 오류가
 * 발견되면 원문에서 다시 계산하면 된다. 원문을 환산값으로 덮어쓰면 복구가 불가능하다.
 *
 * <p>빈 값과 형식 위반은 예외로 던져 저장 자체를 막는다. 0 을 넣어 조용히 넘기면
 * 그 행이 생산지시·BOM·발주에서 전부 사라진다.
 */
public final class JigSetParser {

	/** 숫자, 또는 숫자를 '/' 로 이은 형태만 허용. 자릿수를 제한해 오버플로를 막는다. */
	private static final Pattern FORMAT = Pattern.compile("^\\d{1,4}(?:/\\d{1,4})*$");

	private JigSetParser() {}

	/**
	 * @param raw      화면/엑셀에서 온 JIG SET 원문 (예: "1/1")
	 * @param rowLabel 오류 메시지에 넣을 행 식별자 (품목명 등). 어느 행인지 알려주지 않으면
	 *                 사용자가 수십 행 중에서 찾지 못한다.
	 * @return 환산 수량
	 * @throws IllegalArgumentException 빈 값 / 형식 위반 / 합계 0
	 */
	public static int parse(String raw, String rowLabel) {
		String s = (raw == null) ? "" : raw.trim();
		String label = (rowLabel == null || rowLabel.isBlank()) ? "행" : rowLabel;

		if (s.isEmpty()) {
			throw new IllegalArgumentException(label + " : JIG SET 을 입력하세요.");
		}
		if (!FORMAT.matcher(s).matches()) {
			throw new IllegalArgumentException(
				label + " : JIG SET 은 숫자 또는 1/1 형식만 됩니다. (입력값: " + s + ")");
		}

		int sum = 0;
		for (String part : s.split("/")) {
			sum += Integer.parseInt(part);
		}
		if (sum <= 0) {
			throw new IllegalArgumentException(label + " : JIG SET 값이 0 입니다.");
		}
		return sum;
	}

	/**
	 * 저장 전 일괄 검증용. 잘못된 행이 있으면 메시지를, 없으면 {@code null} 을 반환한다.
	 * 한 행씩 알리면 사용자가 고치고 저장하기를 반복하므로 전부 모아서 돌려준다.
	 */
	public static String validateAll(java.util.List<String[]> rawAndLabels) {
		java.util.List<String> errors = new java.util.ArrayList<>();
		for (String[] pair : rawAndLabels) {
			try {
				parse(pair[0], pair[1]);
			} catch (IllegalArgumentException e) {
				errors.add("· " + e.getMessage());
			}
		}
		return errors.isEmpty() ? null : "JIG SET 을 확인하세요.\n\n" + String.join("\n", errors);
	}
}