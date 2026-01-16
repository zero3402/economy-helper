// 経済コラムデータ - 日本語
const COLUMNS_DATA = [
    {
        id: '01',
        title: 'インフレの二つの顔',
        subtitle: '国家の通貨政策と私の給料が消える事件',
        description: '給料が上がったのに、なぜ財布は空っぽ？インフレ時代、マクロ経済とミクロ経済の観点から資産を守る方法を学びます。',
        readTime: 5,
        keywords: 'インフレ, 物価上昇, マクロ経済, ミクロ経済, 金融政策, 金利',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. はじめに：なぜ私の口座はいつも空っぽなのか？</h2>
                <p class="text-slate-700 leading-relaxed mb-4">会社員Aさんは今年、年収が3%上がりました。数字上は収入が増えたはずなのに、仕事帰りにスーパーで買い物をして何度か外食すると、預金残高は去年よりも早く底をつきます。これは気のせいではありません。</p>
                <p class="text-slate-700 leading-relaxed mb-4">国家全体の経済の流れを扱う<strong>マクロ経済学</strong>の波が、個々の家計のやりくりを扱う<strong>ミクロ経済学</strong>の領域を覆ったためです。インフレ時代に大切な資産を守るために、この二つの視点の違いとつながりを実生活の事例で分析してみましょう。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. マクロ経済的視点：「国がばらまいたお金が波となって戻ってくる」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロ経済学は国家全体の「森」を見ます。ここでインフレとは、通貨量と金利という巨大なダムの水門を調節する問題です。</p>
                <div class="bg-blue-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-blue-800 mb-2">実例：給付金と市場の流動性</h3>
                    <p class="text-slate-700">パンデミック当時、世界中の政府は経済麻痺を防ぐために莫大な資金を市場に投入しました。マクロ的視点ではこれは「総需要」を支える役割を果たしましたが、同時に通貨の希少性を低下させました。市場にお金が出回りすぎると（マクロ）、結局、通貨一単位で買える物の量が減る「物価上昇」が必然的に続きます。</p>
                </div>
                <p class="text-slate-700 leading-relaxed"><strong>中央銀行の介入：</strong>物価があまりにも急激に上がると、中央銀行は基準金利を引き上げます。これは市場のお金を再び銀行ダムに吸い込んで波の高さを下げようとするマクロ的処方です。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. ミクロ経済的視点：「メニュー価格の裏に隠された熾烈な生存戦略」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">ミクロ経済学は個別主体である「木」を見ます。マクロ的な物価上昇という嵐の中で、商人と消費者はそれぞれの方法で生き残ろうとします。</p>
                <div class="bg-green-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-green-800 mb-2">実例：なじみのレストランの悩みと「価格決定力」</h3>
                    <p class="text-slate-700">マクロ的に食材費が上がったとき、すべてのレストランが同じように価格を上げるでしょうか？いいえ。ミクロ的視点で、味とサービスで独自の地位を持つレストラン（価格決定力のある企業）は堂々と価格を上げます。一方、競争が激しく差別化がないレストランは客離れを恐れて価格を上げられず、自ら利益を削って耐えます。</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. マクロとミクロの衝突：「平均の罠」とシュリンクフレーション</h2>
                <p class="text-slate-700 leading-relaxed mb-4">政府はマクロ指標を根拠に「物価上昇率が鈍化した」と言いますが、一般人が体感する物価は依然として熱いです。ここにマクロとミクロの乖離があります。</p>
                <div class="bg-yellow-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-yellow-800 mb-2">実例：お菓子袋の「空気」とシュリンクフレーション</h3>
                    <p class="text-slate-700">政府が物価を監視すると（マクロ）、企業はミクロ的に価格はそのままにして容量を減らす<strong>「シュリンクフレーション」</strong>戦略を使います。統計上の数字では物価は安定しているように見えますが、消費者がスーパーで感じる実質的な量は減っています。</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 一般人のためのインフレ生存戦略</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロの流れを読み、ミクロの行動を修正する必要があります。</p>
                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">マクロ的視野（市場の天気確認）</h3>
                        <p class="text-slate-700">金利引き上げ期（マクロ）には、無理な借金で資産を増やすよりも負債を返済することが、ミクロ的に最も高い収益率を出す選択です。</p>
                    </div>
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">ミクロ的視野（代替品を探す）</h3>
                        <p class="text-slate-700">インフレで価格が急騰した品目に執着するよりも、自分の効用（満足感）を維持できるコスパの良い代替品を積極的に発掘しましょう。</p>
                    </div>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 結論：経済リテラシーが財布を守る</h2>
                <p class="text-slate-700 leading-relaxed">インフレは大げさな経済学用語ではありません。それは今日の昼食メニューを悩む私の選択と、国家が発行した通貨の価値が出会う地点で発生する現実です。マクロ経済という大きな波を変えることはできませんが、ミクロ経済という自分の船をどう操縦するかによって、目的地に安全に到着できます。</p>
            </section>
        `
    },
    {
        id: '02',
        title: 'スタグフレーションの恐怖',
        subtitle: '景気は冷たく物価は熱い「不快な同居」',
        description: '商売は不振なのに、なぜ物価は上がり続けるのでしょうか？景気後退とインフレが同時に起こるスタグフレーションの原因と対策を分析します。',
        readTime: 5,
        keywords: 'スタグフレーション, 景気後退, インフレ, 供給ショック, 金融政策',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. はじめに：「商売が悪いのに、なぜ物価は上がり続けるのか？」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">私たちは通常、景気が悪いと商品が売れずに物価が下がり、景気が良いと消費が増えて物価が上がると学びます。これが経済の一般的な好循環構造です。</p>
                <p class="text-slate-700 leading-relaxed">しかし、最近私たちが経験している現実はかなり異なります。周辺の店舗には「テナント募集」の貼り紙が増え、失業の恐怖が迫る中、スーパーの食材費やガス代は容赦なく高騰しています。このような奇妙な現象を経済学では<strong>スタグフレーション</strong>と呼びます。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. マクロ経済的視点：システムのエンジン過負荷と政策のジレンマ</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロ経済学は、国という巨大な機械がきちんと動いているかを確認する学問です。スタグフレーション状況では、この機械が深刻な誤作動を起こします。</p>
                <div class="bg-red-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-red-800 mb-2">実例：エネルギー価格高騰と「供給ショック」</h3>
                    <p class="text-slate-700">スタグフレーションの主犯は主に「供給側」にあります。例えば、地政学的紛争により国際原油や天然ガス価格がマクロ的に急騰したとします。エネルギーはすべての産業の基礎であるため、国家全体の生産コストを引き上げます。</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. ミクロ経済的視点：生存のための個別主体の必死の変化</h2>
                <p class="text-slate-700 leading-relaxed mb-4">ミクロ経済学は、このような巨大な嵐の中で地元のお店のオーナーや私たちの家族がどのように行動を変えるかを顕微鏡で観察します。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. マクロとミクロの連結：「失業の恐怖」と「生活苦」の二重奏</h2>
                <p class="text-slate-700 leading-relaxed mb-4">スタグフレーションが怖い本当の理由は、マクロ現象である失業がミクロの苦痛である生活苦と出会うとき、シナジーを生むからです。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 一般人のためのスタグフレーション時代の生存戦略</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロ的な不況を変えることはできませんが、ミクロ的な自分の人生の構造は変えることができます。</p>
                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">ミクロ的「コスト効率化」の最大化</h3>
                        <p class="text-slate-700">インフレが続く間は現金の価値が下がります。しかし、景気後退期には収入が不安定になるため、当面必要のない資産は整理し、固定支出（サブスク、通信費など）をミクロ的に徹底的にダイエットする必要があります。</p>
                    </div>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 結論：冷静な頭と温かい買い物かご管理</h2>
                <p class="text-slate-700 leading-relaxed">スタグフレーションは国家にとっては政策的失敗の結果かもしれませんが、個人にとっては経済的忍耐力を試す時期です。森（マクロ）が乾いていくときは、深く根を張った木（ミクロ的競争力）だけが生き残ります。</p>
            </section>
        `
    },
    {
        id: '03',
        title: '量的緩和と引き締め',
        subtitle: '国が揺さぶるお金の波、私の人生は安全か？',
        description: '世の中にお金が多くなったというのに、なぜ私のお金はないのでしょう？量的緩和と引き締め政策が個人の資産に与える影響を分析します。',
        readTime: 5,
        keywords: '量的緩和, 引き締め, 金融政策, 金利, 資産価格, 投資戦略',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. はじめに：「世の中にお金が多すぎると言うのに、なぜ私のお金はないのか？」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">ほんの数年前まで、私たちは「株をしなければバカ」「全財産をつぎ込んでも家を買うべき」という言葉を耳にたこができるほど聞きました。ところがある瞬間から突然「現金が王様」「金利が怖くて何もできない」という雰囲気に反転しました。</p>
                <p class="text-slate-700 leading-relaxed">このような極端な温度差は、国が市場のお金の蛇口を開け閉めするマクロ経済政策である<strong>量的緩和と引き締め</strong>のために発生します。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. マクロ経済的視点：「ダムの水門を開けて乾いた土地を潤す」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロ経済学で量的緩和（QE）は、中央銀行が直接市場にお金を供給して景気後退を防ぐ「心肺蘇生」のようなものです。</p>
                <div class="bg-blue-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-blue-800 mb-2">実例：パンデミック時期の「お金の洪水」</h3>
                    <p class="text-slate-700">景気が止まる危機に瀕すると、政府と中央銀行はマクロ的次元で金利を0%台に引き下げ、市場の債券を買い取り現金を供給しました。マクロ的目標は「企業の倒産防止」と「雇用維持」でしたが、副次的効果として通貨の希少性が急激に低下しました。</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. ミクロ経済的視点：「個人のレバレッジと金利爆弾、そして資産の再編」</h2>
                <p class="text-slate-700 leading-relaxed mb-4">ミクロ経済学は、このようなマクロ的な波の中で個別の家計や投資家がどのような「選択」をするかを追跡します。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. マクロとミクロの連結：「富の移動」とその影</h2>
                <p class="text-slate-700 leading-relaxed mb-4">量的緩和と引き締めは、単にお金の量を調節することを超えて、階層間の富の再編をもたらします。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. 一般人のための流動性の波に乗る戦略</h2>
                <p class="text-slate-700 leading-relaxed mb-4">マクロ的な天気を変えることができないなら、自分の船（ミクロ的資産）を頑丈に補修しなければなりません。</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. 結論：国の政策は天気予報、私の選択は傘</h2>
                <p class="text-slate-700 leading-relaxed">量的緩和と引き締めは、私たちが拒否できない経済的季節のようなものです。マクロ経済という巨大な車輪は止まりません。その車輪に轢かれないためには、今が水門が開いている時なのか閉じている時なのかを絶えず確認しなければなりません。</p>
            </section>
        `
    }
];

// コラムI18N
const COLUMNS_I18N = {
    TITLE: '経済コラム',
    SUBTITLE: 'ミクロからマクロまで',
    DESCRIPTION: '日常生活ですぐに活用できる経済知識を楽しく学びましょう',
    SEARCH_PLACEHOLDER: 'コラム検索...',
    READ_MORE: '続きを読む',
    READ_TIME: '分で読める',
    NO_RESULTS: '検索結果がありません',
    BACK_TO_LIST: '一覧に戻る',
    SHARE: '共有',
    RELATED_COLUMNS: '関連コラム',
    PREV_COLUMN: '前の記事',
    NEXT_COLUMN: '次の記事'
};
