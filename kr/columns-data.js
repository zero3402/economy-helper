// 경제 칼럼 데이터 - 한국어
const COLUMNS_DATA = [
    {
        id: '01',
        title: '인플레이션의 두 얼굴',
        subtitle: '국가의 통화 잔치와 내 월급의 실종사건',
        description: '연봉이 올랐는데 왜 통장은 텅장일까요? 인플레이션 시대, 거시경제와 미시경제의 관점에서 내 자산을 지키는 방법을 알아봅니다.',
        readTime: 5,
        keywords: '인플레이션, 물가상승, 거시경제, 미시경제, 통화정책, 금리',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. 서론: 왜 내 통장은 늘 '텅장'이 될까?</h2>
                <p class="text-slate-700 leading-relaxed mb-4">직장인 A씨는 올해 연봉이 3% 올랐습니다. 분명 수치상으로는 소득이 늘었지만, 퇴근길 마트에서 장을 보고 외식을 몇 번 하고 나면 통장 잔고는 작년보다 더 빨리 바닥을 드러냅니다. 이것은 기분 탓이 아닙니다.</p>
                <p class="text-slate-700 leading-relaxed mb-4">국가 전체의 경제 흐름을 다루는 <strong>거시경제학(Macroeconomics)</strong>의 파도가 개별 가계의 살림살이를 다루는 <strong>미시경제학(Microeconomics)</strong>의 영역을 덮쳤기 때문입니다. 인플레이션 시대, 내 소중한 자산을 지키기 위해 이 두 관점의 차이와 연결고리를 실생활 사례로 분석해 보겠습니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. 거시경제적 관점: "국가가 뿌린 돈, 파도가 되어 돌아오다"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시경제학은 국가 전체의 '숲'을 봅니다. 여기서 인플레이션은 통화량과 금리라는 거대한 댐의 수문을 조절하는 문제입니다.</p>

                <div class="bg-blue-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-blue-800 mb-2">실사례: 재난지원금과 시중 유동성</h3>
                    <p class="text-slate-700">팬데믹 당시 전 세계 정부는 경제 마비를 막기 위해 막대한 자금을 시중에 풀었습니다. 거시적 관점에서 이는 '총수요'를 받쳐주는 역할을 했지만, 동시에 화폐의 희소성을 떨어뜨렸습니다. 시중에 돈이 너무 많이 풀리면(거시), 결국 화폐 한 단위로 살 수 있는 물건의 양이 줄어드는 '물가 상승'이 필연적으로 뒤따릅니다.</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>중앙은행의 개입:</strong> 물가가 너무 가파르게 오르면 중앙은행은 기준금리를 올립니다. 이는 시중의 돈을 다시 은행 댐으로 빨아들여 파도의 높이를 낮추려는 거시적인 처방입니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. 미시경제적 관점: "메뉴판 가격 뒤에 숨겨진 치열한 생존 전략"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">미시경제학은 개별 주체인 '나무'를 봅니다. 거시적인 물가 상승이라는 폭풍 속에서 상인과 소비자들은 각자의 방식으로 살아남으려 합니다.</p>

                <div class="bg-green-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-green-800 mb-2">실사례: 단골 식당의 고민과 '가격 결정력'</h3>
                    <p class="text-slate-700">거시적으로 식재료비가 올랐을 때, 모든 식당이 똑같이 가격을 올릴까요? 아닙니다. 미시적 관점에서 맛과 서비스로 독보적인 위치에 있는 식당(가격 결정력이 있는 기업)은 당당히 가격을 올립니다. 반면, 경쟁이 치열하고 차별점이 없는 식당은 손님이 끊길까 봐 가격을 못 올리고 스스로 마진을 깎으며 버팁니다.</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>소비자의 '대체 효과':</strong> 미시경제학의 핵심은 선택입니다. 소고기 가격이 너무 오르면 소비자들은 상대적으로 저렴한 돼지고기나 닭고기를 찾습니다. 거시 통계는 '육류 물가 상승'으로 묶어 말하지만, 미시적으로는 소비자들이 더 나은 효용을 위해 소비 품목을 바꾸는 치열한 계산이 일어납니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. 거시와 미시의 충돌: '평균의 함정'과 슈링크플레이션</h2>
                <p class="text-slate-700 leading-relaxed mb-4">정부는 거시 지표를 근거로 "물가 상승률이 둔화되었다"고 말하지만, 일반인이 체감하는 물가는 여전히 뜨겁습니다. 여기에 거시와 미시의 괴리가 있습니다.</p>

                <div class="bg-yellow-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-yellow-800 mb-2">실사례: 과자 봉지 속의 '질소'와 슈링크플레이션</h3>
                    <p class="text-slate-700">정부가 물가를 감시하면(거시), 기업들은 미시적으로 가격은 그대로 두되 용량을 줄이는 <strong>'슈링크플레이션(Shrinkflation)'</strong> 전략을 씁니다. 통계 숫자상 물가는 안정된 것처럼 보이지만, 소비자가 마트에서 느끼는 실질적인 양은 줄어든 것입니다. 거시적 지표라는 '평균의 함정' 뒤에서 미시적인 경제 주체들이 교묘하게 대응하고 있는 사례입니다.</p>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 일반인을 위한 인플레이션 생존 전략</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시적 흐름을 읽고 미시적 행동을 수정해야 합니다.</p>

                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">거시적 안목 (시장의 날씨 확인)</h3>
                        <p class="text-slate-700">금리 인상기(거시)에는 무리한 대출로 자산을 늘리기보다 부채를 상환하는 것이 미시적으로 가장 높은 수익률을 내는 선택입니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">미시적 안목 (대체재 찾기)</h3>
                        <p class="text-slate-700">인플레이션으로 인해 가격이 폭등한 품목에 집착하기보다, 나의 효용(만족감)을 유지할 수 있는 가성비 높은 대체재를 적극적으로 발굴해야 합니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">가격 결정력을 가진 자산에 투자</h3>
                        <p class="text-slate-700">내가 소비만 하는 것이 아니라, 물가 상승분을 가격에 전가할 수 있는 강력한 미시적 경쟁력을 가진 대상(우량 기업 등)에 내 자산을 태워야 합니다.</p>
                    </div>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 결론: 경제적 리터러시가 내 통장을 지킨다</h2>
                <p class="text-slate-700 leading-relaxed mb-4">인플레이션은 거창한 경제학 용어가 아닙니다. 그것은 오늘 점심 메뉴를 고민하는 나의 선택과, 국가가 발행한 화폐의 가치가 만나는 지점에서 발생하는 현실입니다.</p>
                <p class="text-slate-700 leading-relaxed">거시 경제라는 큰 파도를 바꿀 수는 없지만, 미시 경제라는 나의 배를 어떻게 조종하느냐에 따라 목적지에 안전하게 도착할 수 있습니다. 숲의 날씨(거시)를 주시하면서, 오늘 내 장바구니에 담긴 물건(미시)이 정말 합리적인 선택인지 끊임없이 질문하십시오. 그것이 자본주의라는 바다에서 표류하지 않는 유일한 길입니다.</p>
            </section>
        `
    },
    {
        id: '02',
        title: '스태그플레이션의 공포',
        subtitle: "경기는 차갑고 물가는 뜨거운 '불쾌한 동거'",
        description: '장사도 안되는데 물건값은 왜 오를까요? 경기 침체와 물가 상승이 동시에 오는 스태그플레이션의 원인과 대응 전략을 알아봅니다.',
        readTime: 5,
        keywords: '스태그플레이션, 경기침체, 물가상승, 공급쇼크, 금리정책',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. 서론: "장사도 안되는데 물건값은 왜 자꾸 오를까?"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">우리는 보통 경기가 나쁘면 물건이 안 팔려서 물가가 떨어지고, 경기가 좋으면 소비가 늘어 물가가 오른다고 배웁니다. 이것이 경제의 일반적인 선순환 구조입니다.</p>
                <p class="text-slate-700 leading-relaxed mb-4">하지만 최근 우리가 겪는 현실은 사뭇 다릅니다. 주변 상가에는 '임대 문의' 붙은 가게들이 늘어나고 실업의 공포가 엄습하는데, 마트의 식재료값과 가스비는 자비 없이 치솟습니다.</p>
                <p class="text-slate-700 leading-relaxed">이러한 기현상을 경제학에서는 <strong>스태그플레이션(Stagflation)</strong>이라고 부릅니다. 국가 경제의 성장이 멈추는 거시적 침체와 내 삶의 비용이 폭등하는 미시적 고통이 결합한 최악의 시나리오를 분석해 봅니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. 거시경제적 관점: 시스템의 엔진 과부하와 정책의 딜레마</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시경제학은 국가라는 거대한 기계가 제대로 돌아가는지 확인하는 학문입니다. 스태그플레이션 상황에서 이 기계는 심각한 오작동을 일으킵니다.</p>

                <div class="bg-red-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-red-800 mb-2">실사례: 에너지 가격 폭등과 '공급 쇼크'</h3>
                    <p class="text-slate-700">스태그플레이션의 주범은 주로 '공급 측면'에 있습니다. 예를 들어, 지정학적 갈등으로 인해 국제 유가나 천연가스 가격이 거시적으로 급등했다고 가정해 봅시다. 에너지는 모든 산업의 기초이기에 국가 전체의 생산 비용을 끌어올립니다. 생산비가 오르면 기업은 생산을 줄이게 되고(경기 침체), 동시에 제품 가격은 올리게 됩니다(물가 상승).</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>중앙은행의 '외통수':</strong> 물가를 잡으려 금리를 올리면 빚더미에 앉은 가계와 기업이 무너지고(거시적 파산), 경기를 살리려 금리를 낮추면 물가가 폭주합니다. 거시적 차원에서 정부가 쓸 수 있는 카드가 극히 제한되는 지점이 바로 여기입니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. 미시경제적 관점: 생존을 위한 개별 주체들의 필사적인 변화</h2>
                <p class="text-slate-700 leading-relaxed mb-4">미시경제학은 이러한 거대한 폭풍 속에서 동네 사장님과 우리 가족이 어떻게 행동을 바꾸는지 현미경으로 들여다봅니다.</p>

                <div class="bg-orange-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-orange-800 mb-2">실사례 1: 사장님의 고육지책과 '가성비'의 실종</h3>
                    <p class="text-slate-700">거시적으로 원자재값이 올랐을 때, 동네 식당 사장님은 미시적인 선택에 직면합니다. 손님이 줄어드는 것을 감수하고 메뉴판 가격을 올릴 것인가, 아니면 가격은 유지하되 제공하는 반찬 수를 줄여 비용을 아낄 것인가? 만약 사장님이 후자를 선택한다면, 미시경제학에서 말하는 '효용(만족감)'은 급격히 감소합니다.</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>소비자의 '예산 제약'과 극단적인 소비 절벽:</strong> 미시적으로 소득이 정체된 가계는 지출 우선순위를 재조정합니다. 당장 없으면 안 되는 '필수재(쌀, 전기)'는 울며 겨자 먹기로 구매하지만, 없어도 사는데 지장 없는 '선택재(의류, 취미)'는 완전히 포기합니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. 거시와 미시의 연결: '실업의 공포'와 '생활고'의 이중주</h2>
                <p class="text-slate-700 leading-relaxed mb-4">스태그플레이션이 무서운 진짜 이유는 거시적 현상인 실업이 미시적 고통인 생활고와 만날 때 시너지를 내기 때문입니다.</p>

                <div class="bg-purple-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-purple-800 mb-2">실사례: 구인 광고의 소멸과 알뜰폰 수요 폭증</h3>
                    <p class="text-slate-700">국가가 거시적으로 경기 불황에 빠지면 기업은 채용을 중단합니다. 이때 미시적으로 구직자들은 더 낮은 임금을 수용하거나, 통신비를 아끼기 위해 알뜰폰으로 갈아타는 등 생존을 위한 '다운사이징'에 돌입합니다.</p>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 일반인을 위한 스태그플레이션 시대 생존 전략</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시적 침체를 바꿀 수는 없지만, 미시적인 내 삶의 구조는 바꿀 수 있습니다.</p>

                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">미시적 '비용 효율화' 극대화</h3>
                        <p class="text-slate-700">인플레이션이 지속되는 동안은 현금의 가치가 떨어집니다. 하지만 경기 침체기에는 소득이 불안정해지므로, 당장 필요하지 않은 자산은 정리하고 고정 지출(구독료, 통신비 등)을 미시적으로 철저히 다이어트해야 합니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">노동 가치의 희소성 확보</h3>
                        <p class="text-slate-700">경기가 나쁠 때 가장 먼저 타격을 입는 것은 대체 가능한 노동력입니다. 거시적 불황에도 시장이 필요로 하는 미시적 전문 기술을 확보하여 나의 '몸값'을 인플레이션 방어 수단으로 만들어야 합니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">부채의 고정 비용화</h3>
                        <p class="text-slate-700">금리 변동성이 큰 시기에는 미시적으로 내 부채가 변동 금리인지 확인하고, 감당 가능한 수준의 고정 금리로 전환하여 예상치 못한 이자 폭탄을 막아야 합니다.</p>
                    </div>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 결론: 차가운 머리와 따뜻한 장바구니 관리</h2>
                <p class="text-slate-700 leading-relaxed mb-4">스태그플레이션은 국가에게는 정책적 실패의 결과일지 모르지만, 개인에게는 경제적 인내심을 시험하는 시기입니다. 거시 경제가 보내는 "침체"의 신호를 무시하지 마십시오. 동시에 미시적으로 내 삶에서 불필요한 거품이 어디에 있는지 냉정하게 도려내야 합니다.</p>
                <p class="text-slate-700 leading-relaxed">숲(거시)이 메말라 갈 때는 깊게 뿌리 내린 나무(미시적 경쟁력)만이 살아남습니다. 단순히 "경기가 안 좋다"고 한탄하기보다, 이 어려운 기후 속에서 내가 지켜야 할 최소한의 자산과 버려야 할 우선순위를 정하는 지혜가 필요합니다.</p>
            </section>
        `
    },
    {
        id: '03',
        title: '양적 완화와 긴축',
        subtitle: '국가가 흔드는 돈의 파도, 내 삶은 안전한가?',
        description: '세상에 돈이 많아졌다는데 왜 내 돈은 없을까요? 양적 완화와 긴축 정책이 개인의 자산에 미치는 영향을 분석합니다.',
        readTime: 5,
        keywords: '양적완화, 긴축정책, 통화정책, 금리, 자산가격, 투자전략',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. 서론: "세상에 돈이 너무 많아졌다는데, 왜 내 돈은 없지?"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">불과 몇 년 전, 우리는 '주식 안 하면 바보', '영끌해서라도 집 사야 한다'는 말을 귀에 못이 박이도록 들었습니다. 그러다 갑자기 어느 순간부터는 '현금이 왕이다', '금리가 무서워서 아무것도 못 하겠다'는 분위기로 반전되었습니다.</p>
                <p class="text-slate-700 leading-relaxed">이러한 극단적인 온도 차이는 국가가 시중의 돈줄을 풀고 조이는 거시경제(Macroeconomics) 정책인 <strong>양적 완화와 긴축</strong> 때문에 발생합니다. 국가라는 거대한 댐의 수문이 열리고 닫힐 때, 우리 개인의 지갑(미시)에는 어떤 일이 벌어지는지 실생활 사례로 파헤쳐 보겠습니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. 거시경제적 관점: "댐의 수문을 열어 마른 땅을 적시다"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시경제학에서 양적 완화(Quantitative Easing)는 중앙은행이 직접 시장에 돈을 뿌려 경기 침체를 막는 '심폐소생술'과 같습니다.</p>

                <div class="bg-blue-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-blue-800 mb-2">실사례: 팬데믹 시기의 '돈의 홍수'</h3>
                    <p class="text-slate-700">경기가 멈출 위기에 처하자 정부와 중앙은행은 거시적 차원에서 금리를 0%대로 낮추고 시장의 채권을 사들이며 현금을 공급했습니다. 거시적 목표는 '기업의 부도 방지'와 '고용 유지'였지만, 부수적인 효과로 화폐의 희소성이 급격히 떨어졌습니다.</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>긴축(Tightening)의 등장:</strong> 물가가 감당할 수 없을 만큼 오르면 중앙은행은 수문을 닫습니다. 금리를 올리고 시중의 돈을 다시 회수하는 '양적 긴축'에 돌입합니다. 이는 거시적으로 과열된 경제의 열기를 식히는 과정입니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. 미시경제적 관점: "개인의 영끌과 이자 폭탄, 그리고 자산의 재편"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">미시경제학은 이러한 거시적 파도 속에서 개별 가계와 투자자가 어떤 '선택'을 내리는지 추적합니다.</p>

                <div class="bg-green-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-green-800 mb-2">실사례 1: 저금리 시대의 '레버리지' 유혹</h3>
                    <p class="text-slate-700">거시적 양적 완화로 금리가 낮아졌을 때, 미시적 주체인 개인들은 계산기를 두드립니다. "은행 이자가 2%인데, 주식이나 부동산 수익률이 10%라면 빚을 내서 투자하는 게 합리적이다"라는 결론에 도달합니다.</p>
                </div>

                <div class="bg-red-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-red-800 mb-2">실사례 2: 고금리 시대의 '역머니무브'와 소비 위축</h3>
                    <p class="text-slate-700">긴축이 시작되고 금리가 5~6%로 치솟으면(거시), 미시적 주체들의 행동은 180도 바뀝니다. "위험한 주식보다는 안전한 예금이 낫다"며 돈을 다시 은행으로 옮깁니다. 또한, 월급의 절반이 대출 이자로 나가기 시작하면, 가장 먼저 외식비와 쇼핑비를 줄입니다.</p>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. 거시와 미시의 연결: '부의 이동'과 그늘</h2>
                <p class="text-slate-700 leading-relaxed mb-4">양적 완화와 긴축은 단순히 돈의 양을 조절하는 것을 넘어, 계층 간 부의 재편을 가져옵니다.</p>

                <div class="bg-purple-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-purple-800 mb-2">자산 양극화의 심화</h3>
                    <p class="text-slate-700">양적 완화 시기에 자산을 미리 보유했던 사람들은 거시적 유동성의 수혜를 입어 큰 부를 쌓습니다. 반면, 오직 노동 소득(미시적 임금)에만 의존한 사람들은 자산 가격 상승 속도를 따라가지 못해 실질적인 가난을 경험합니다.</p>
                </div>

                <p class="text-slate-700 leading-relaxed"><strong>긴축의 역설:</strong> 긴축은 물가를 잡기 위한 정의로운 조치처럼 보이지만, 미시적으로는 부채가 많은 서민층에게 가장 먼저 타격을 입힙니다. 거시적 경제 안정이라는 명분 아래, 미시적으로는 '영끌족'의 고통이 수반되는 비극이 발생합니다.</p>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 일반인을 위한 유동성 파도 타기 전략</h2>
                <p class="text-slate-700 leading-relaxed mb-4">거시적 날씨를 바꿀 수 없다면, 내 배(미시적 자산)를 튼튼하게 보수해야 합니다.</p>

                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">거시적 시그널 읽기</h3>
                        <p class="text-slate-700">중앙은행이 "금리 인상을 고려 중"이라고 말할 때(거시), 미시적으로는 부채의 고정 금리 전환이나 현금 비중 확대를 준비해야 합니다. 소나기가 오기 전에 우산을 사는 것과 같습니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">미시적 '한계 비용' 계산</h3>
                        <p class="text-slate-700">돈이 풀릴 때는 '투자의 기회비용'이 낮아지지만, 돈이 조여질 때는 '부채의 비용'이 기하급수적으로 늘어납니다. 내 소득에서 이자가 차지하는 비중을 철저히 관리하는 미시적 수비가 필요합니다.</p>
                    </div>

                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">심리적 편향 경계</h3>
                        <p class="text-slate-700">남들이 다 돈을 벌 때(양적 완화 말기) 뛰어들고, 남들이 공포에 질려 팔 때(긴축 절정기) 투자를 포기하는 것은 인간의 본능입니다. 미시적 선택을 내릴 때 거시적 사이클의 위치를 냉정하게 대조해 보는 습관이 부자를 만듭니다.</p>
                    </div>
                </div>
            </section>

            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 결론: 국가의 정책은 기상예보, 내 선택은 우산</h2>
                <p class="text-slate-700 leading-relaxed mb-4">양적 완화와 긴축은 우리가 거부할 수 없는 경제적 계절과 같습니다. 국가가 돈을 풀 때는 따뜻한 봄 같지만, 그 끝에는 항상 추운 긴축의 겨울이 기다리고 있습니다.</p>
                <p class="text-slate-700 leading-relaxed">거시 경제라는 거대한 수레바퀴는 멈추지 않습니다. 그 바퀴에 깔리지 않으려면, 지금이 수문이 열린 때인지 닫힌 때인지 끊임없이 확인해야 합니다. 단순히 "세상 살기 힘들다"는 푸념보다, 지금의 거시적 흐름 속에서 내가 할 수 있는 미시적인 최선의 선택(저축, 투자, 혹은 부채 상환)이 무엇인지 고민하십시오.</p>
            </section>
        `
    }
];

// 칼럼 I18N
const COLUMNS_I18N = {
    TITLE: '경제 칼럼',
    SUBTITLE: '미시에서 거시까지',
    DESCRIPTION: '실생활에서 바로 적용할 수 있는 경제 지식을 쉽고 재미있게 배워보세요',
    SEARCH_PLACEHOLDER: '칼럼 검색...',
    READ_MORE: '자세히 읽기',
    READ_TIME: '분 읽기',
    NO_RESULTS: '검색 결과가 없습니다',
    BACK_TO_LIST: '목록으로 돌아가기',
    SHARE: '공유하기',
    RELATED_COLUMNS: '관련 칼럼',
    PREV_COLUMN: '이전 글',
    NEXT_COLUMN: '다음 글'
};
