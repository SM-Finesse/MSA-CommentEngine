package com.finesse.api.pack;

/**
 * 대체 문장 칸. 앞의 5칸은 모든 팩에서 필수, weak 2칸은 선택 (11/15 추가).
 * <p>
 * 고르는 규칙 (방향은 값의 부호, 강도는 영역 — 모놀리식 10/26 A안과 같음):
 * <pre>
 * 표시 자릿수로 반올림한 값이 0        → zero
 * 영역 high, 값 양수                 → high
 * 영역 low,  값 음수                 → low
 * 영역 normal                        → normal_pos (양수) · normal_neg (음수)
 * 영역 low,  값 양수 (영역과 부호가 반대) → weak_pos, 없으면 normal_pos
 * 영역 high, 값 음수 (영역과 부호가 반대) → weak_neg, 없으면 normal_neg
 * </pre>
 * 영역은 분포 안의 위치라서, 대부분이 음수인 지표는 high 영역이어도 값이 음수일 수 있다.
 * weak 칸이 없던 때는 이 경우에도 high·low 문장을 써서 "뚜렷하게 앞섭니다(-0.03)"처럼 앞뒤가 맞지 않았다.
 */
public record Phrases(String high, String low, String normalPos, String normalNeg, String zero,
                      String weakPos, String weakNeg) {
}
