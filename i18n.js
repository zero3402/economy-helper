// Economy Helper - Internationalization (i18n) System

// Detect user's language based on browser/OS settings
function detectLanguage() {
    // Check if we already have the language preference stored
    const storedLang = localStorage.getItem('eh_language');
    if (storedLang) {
        return storedLang;
    }

    // Get browser/OS language settings
    // navigator.languages returns an array of preferred languages (most preferred first)
    // navigator.language returns the primary language
    const languages = navigator.languages || [navigator.language || navigator.userLanguage];

    // Check if any of the preferred languages is Korean
    for (const lang of languages) {
        const langCode = lang.toLowerCase();
        if (langCode.startsWith('ko')) {
            localStorage.setItem('eh_language', 'ko');
            return 'ko';
        }
    }

    localStorage.setItem('eh_language', 'en');
    return 'en';
}

// Get current language
function getCurrentLanguage() {
    return localStorage.getItem('eh_language') || 'en';
}

// Set language manually
function setLanguage(lang) {
    localStorage.setItem('eh_language', lang);
    window.location.reload();
}

// Translations
const translations = {
    en: {
        // Common
        common: {
            search: 'Search',
            all: 'All',
            retakeTest: 'Retake Test',
            shareResults: 'Share Results',
            shareYourResult: 'Share Your Result',
            shareOnTwitter: 'Share on X (Twitter)',
            shareOnFacebook: 'Share on Facebook',
            copyLink: 'Copy Link',
            copied: 'Copied!',
            close: 'Close',
            previous: 'Previous',
            next: 'Next',
            seeResults: 'See Results',
            startTest: 'Start Test'
        },

        // Navigation
        nav: {
            dictionary: 'Dictionary',
            personalityTest: 'Personality Test',
            salaryCalculator: 'Salary Calculator'
        },

        // Personality Test Page
        personalityTest: {
            title: 'Economic Personality Test',
            subtitle: 'Discover your financial personality type through 50 carefully crafted questions across 5 sections.',
            subtitle2: 'Learn about your strengths, weaknesses, and ideal investment strategies.',
            duration: '8-10 Minutes',
            durationDesc: 'Comprehensive analysis',
            questions: '50 Questions',
            questionsDesc: '5 sections, 10 each',
            personalityTypes: '5 Personality Types',
            questionOf: 'Question',
            of: 'of',
            stronglyDisagree: 'Strongly Disagree',
            stronglyAgree: 'Strongly Agree',
            yourEconomicPersonality: 'Your Economic Personality',
            basedOnResponses: 'Based on your responses, here\'s your financial profile',
            youAre: 'You are',
            keyTraits: 'Key Traits',
            strengths: 'Strengths',
            areasToImprove: 'Areas to Improve',
            investorsShareStyle: 'Investors Who Share Your Style',
            compatibilityWithOthers: 'Compatibility with Other Types',
            bestMatch: 'Best Match',
            goodMatch: 'Good Match',
            challenging: 'Challenging'
        },

        // Personality Type Names
        types: {
            safeGuard: 'The Safe Guard',
            aggressiveBull: 'The Aggressive Bull',
            strategicPlanner: 'The Strategic Planner',
            valueSeeker: 'The Value Seeker',
            trendFollower: 'The Trend Follower'
        },

        // Slogans
        slogans: {
            safeGuard: '"Preserving wealth is the first step to building it."',
            aggressiveBull: '"High risk, high return; I ride the waves of the market."',
            strategicPlanner: '"Success is a result of meticulous planning and diversification."',
            valueSeeker: '"A penny saved is a penny earned; smart spending is investing."',
            trendFollower: '"Innovation drives wealth; I invest in the future, today."'
        },

        // Sections
        sections: {
            section1: 'Section 1: Investment Attitude & Risk',
            section2: 'Section 2: Spending Habits & Value Seeking',
            section3: 'Section 3: Information & Technology Trends',
            section4: 'Section 4: Planning & Asset Management',
            section5: 'Section 5: Lifestyle & Philosophy'
        },

        // Questions
        questions: [
            // Section 1
            'I would avoid an investment even if there is a 1% chance of losing the principal.',
            '"High Risk, High Return" is my life motto.',
            'I believe using leverage (loans) is an essential strategy to become wealthy.',
            'The first thing I check when investing is the "maximum potential loss."',
            'I am constantly drawn to skyrocketing stocks or hot topics in the news.',
            'I believe cryptocurrency will become a core asset of the future.',
            'I prefer concentrated investment in one or two stocks I trust over diversification.',
            'I think a market filled with panic is the greatest buying opportunity.',
            'I believe in the power of compounding and am ready to invest for over 10 years.',
            'I feel at peace only when more than 50% of my assets are in cash.',
            // Section 2
            'I compare prices on at least three different websites before making a purchase.',
            '"Brand image" is more important to me than "value for money."',
            'I maximize credit card benefits like airline miles or cash-back rewards.',
            'I track every expense meticulously using a household ledger or app.',
            'My friends have called me "frugal" or "stingy" before.',
            'I regularly review and cancel subscription services I no longer use.',
            'I don\'t hold back when spending on "luxury gifts" for myself.',
            'I make a conscious effort to save on utility bills like electricity and water.',
            'To avoid impulse buying, I leave items in the cart for a few days before paying.',
            'The joy of seeing my bank balance grow is greater than the joy of spending.',
            // Section 3
            'I install new financial apps (Neo-banks, Fintech) as soon as they are released.',
            'I am very interested in future industries like Fintech, AI, and Robotics.',
            'I recognize the value of digital assets like NFTs or Metaverse land.',
            'I am usually the first to tell my friends about new ways to make money.',
            'I don\'t mind reading through complex terms and conditions or public disclosures.',
            'I want to utilize tools like ChatGPT for professional investment analysis.',
            'I am obsessed with finding "hidden gems" or information others don\'t know yet.',
            'I always connect news about interest rates or exchange rates to my own life.',
            'I believe innovative companies are worth investing in even if they are currently in debt.',
            'I believe the faster the world changes, the more opportunities there are to make money.',
            // Section 4
            'I have a specific target amount of assets I want to reach in 10 or 20 years.',
            'I save/invest first when I get paid, then spend what is left.',
            'I maintain a set ratio between stocks, real estate, and cash (Portfolio).',
            'I always keep an emergency fund separate for unexpected expenses.',
            'I know my exact "Net Worth" (Assets minus Liabilities) in numbers today.',
            'I view taking out a loan as a strategic tool rather than something "bad."',
            'I believe retirement planning should start as early as possible, and I\'ve already started.',
            'I live by a budget that categorizes my monthly spending.',
            'I reinvest my profits to maximize the power of compounding.',
            'I believe "patience" is the most important virtue to become wealthy.',
            // Section 5
            'I feel happier during the process of saving money than spending it.',
            'I am willing to sacrifice my time and health to a certain extent to make money.',
            'Achieving "Financial Freedom" is the ultimate goal of my life.',
            'Being "actually wealthy" is more important to me than "looking wealthy" to others.',
            'I am prouder of owning good stocks than driving an expensive car.',
            'I want to build wealth through skill rather than relying on luck like the lottery.',
            'Minimalism (owning less) fits well with my economic worldview.',
            'I think I could give up love or friendship for a massive amount of money.',
            'I enjoy reading biographies of people who have achieved great economic success.',
            'I believe spending money for current happiness (YOLO) is also valuable.'
        ],

        // Traits for each type
        traits: {
            safeGuard: [
                'Prioritizes capital preservation over high returns - would rather earn 3% safely than risk losing anything for 10%',
                'Prefers guaranteed, low-risk investments like government bonds, CDs, and high-yield savings accounts',
                'Maintains emergency funds covering 6-12 months of expenses, sometimes even more',
                'Avoids all forms of debt religiously and pays off credit card balances immediately every month',
                'Values predictability and stability - sleeps better knowing exactly what their money is doing',
                'Thoroughly researches every financial decision, sometimes taking months before committing',
                'Prefers physical assets and tangible investments over abstract financial instruments',
                'Has a deep-seated fear of financial loss stemming from personal experience or family history'
            ],
            aggressiveBull: [
                'Always seeking the next big winning investment - constantly scanning for 10x or 100x opportunities',
                'Comfortable with extreme volatility - a 30% portfolio swing in a week is just another Tuesday',
                'Uses leverage (margin, options, futures) to maximize potential gains beyond available capital',
                'Makes quick, decisive decisions on opportunities - hesitation means missing the trade',
                'Believes personal skill and analysis can consistently beat the market average',
                'Drawn to high-stakes environments - IPOs, pre-market trading, earnings plays, and momentum trades',
                'Views losses as tuition fees for market education rather than failures',
                'Thrives on the adrenaline and excitement that comes with high-risk trading'
            ],
            strategicPlanner: [
                'Never puts all eggs in one basket - maintains carefully calculated asset allocation across multiple classes',
                'Makes every financial decision based on data, research, and analysis rather than emotions or hunches',
                'Focuses on long-term wealth building over 10, 20, or 30+ year horizons',
                'Regularly rebalances portfolio to maintain target allocations and manage risk exposure',
                'Deeply values tax efficiency - uses tax-advantaged accounts, tax-loss harvesting, and strategic timing',
                'Understands and leverages the power of compound growth through consistent reinvestment',
                'Creates detailed financial plans with specific milestones, goals, and contingency scenarios',
                'Treats personal finance like running a business - with budgets, forecasts, and performance reviews'
            ],
            valueSeeker: [
                'Never pays full price when a discount, coupon, or cashback opportunity exists',
                'Expert at finding deals - knows every cashback app, credit card perk, and loyalty program',
                'Tracks every expense meticulously using apps, spreadsheets, or detailed budgets',
                'Values quality and durability over brand names - researches cost-per-use before buying',
                'Believes small daily savings compound into massive wealth over time',
                'Finds genuine satisfaction and even joy in the process of saving money',
                'Skilled at negotiating better prices, rates, and terms on everything from cars to cable bills',
                'Views unnecessary spending as literally throwing away future wealth and freedom'
            ],
            trendFollower: [
                'Invests heavily in future technologies - AI, blockchain, clean energy, biotech, space exploration',
                'Comfortable with cryptocurrency, DeFi, NFTs, Web3, and emerging digital asset classes',
                'Early adopter of new financial apps, neobanks, and fintech innovations before mainstream adoption',
                'Values community sentiment, social trading, and collective intelligence from online groups',
                'Believes technology and innovation are the primary drivers of wealth creation in the modern era',
                'Constantly learning about new developments, protocols, and opportunities in emerging spaces',
                'Willing to invest in ideas and visions before they have proven business models or profits',
                'Views traditional finance as outdated and sees disruption as inevitable and investable'
            ]
        },

        // Strengths for each type
        strengths: {
            safeGuard: [
                'Exceptional capital security - your principal is virtually untouchable',
                'Emotional stability during market crashes - remain calm while others panic',
                'Strong liquidity position - always ready for emergencies',
                'Disciplined saving habits that compound over decades',
                'Naturally protected from high-risk scams and frauds',
                'Low stress levels around money',
                'Excellent credit scores from responsible debt management',
                'Ability to weather economic recessions better than most'
            ],
            aggressiveBull: [
                'Potential for truly life-changing returns',
                'Quick reaction time to market opportunities',
                'Deep understanding of market mechanics and trading psychology',
                'Psychological resilience against temporary losses',
                'Decisive action when others are paralyzed by fear',
                'Extensive knowledge of advanced financial instruments',
                'Natural ability to spot asymmetric risk/reward opportunities',
                'Strong conviction in positions'
            ],
            strategicPlanner: [
                'Optimized risk-to-reward ratio through strategic diversification',
                'Highest probability of reaching long-term financial goals',
                'Tax-efficient wealth building',
                'Protected against single asset or sector failure',
                'Strategy scales seamlessly from $1,000 to $10,000,000',
                'Emotional detachment from market noise',
                'Clear visibility into financial future',
                'Ability to automate most investment decisions'
            ],
            valueSeeker: [
                'Exceptional savings rate',
                'Creative and resourceful problem-solving',
                'Rarely falls into consumer debt traps',
                'Can survive and thrive during economic hardships',
                'Lower monthly expenses mean earlier financial independence',
                'Deep appreciation for what you have',
                'Expert knowledge of consumer products and value',
                'Environmental benefits from reduced consumption'
            ],
            trendFollower: [
                'Can capture massive returns by being early',
                'Proficient with cutting-edge financial tools',
                'Access to unique alpha through online communities',
                'Portfolio aligned with technological progress',
                'Investing feels like a passionate hobby',
                'Comfortable with complexity',
                'Strong network of like-minded investors',
                'Adaptive mindset ready to pivot quickly'
            ]
        },

        // Weaknesses for each type
        weaknesses: {
            safeGuard: [
                'Returns often lag behind inflation - purchasing power may decrease',
                'Missed opportunities in bull markets',
                'Over-reliance on traditional bank products with low interest rates',
                'Difficulty adapting to changing economic environments',
                'Wealth grows linearly rather than exponentially',
                'May develop excessive anxiety about any financial risk',
                'Opportunity cost of holding too much cash',
                'Risk of being "penny wise, pound foolish"'
            ],
            aggressiveBull: [
                'High risk of significant or total capital loss',
                'Market volatility can affect mental health and relationships',
                'High trading fees and tax inefficiency',
                'Prone to emotional "revenge trading" after losses',
                'May develop gambling-like addiction patterns',
                'Overconfidence from past wins can lead to future losses',
                'Difficulty maintaining work-life balance',
                'Survivorship bias - remembering wins while forgetting losses'
            ],
            strategicPlanner: [
                'Requires significant upfront research and setup time',
                'The process can feel boring compared to active trading',
                'Analysis paralysis - delaying action while optimizing',
                'May underperform concentrated portfolios in bull markets',
                'Heavy reliance on tracking tools - complexity can overwhelm',
                'Rigid adherence may miss obvious opportunities',
                'Can become obsessive about optimization',
                'May come across as preachy when discussing finance'
            ],
            valueSeeker: [
                'Time spent hunting deals may exceed value saved',
                'May sacrifice quality for lower prices',
                'Can be perceived as cheap or stingy',
                'Scarcity mindset may limit vision for earning more',
                'May never fully enjoy the fruits of labor',
                'Penny-pinching while ignoring major financial opportunities',
                'Decision fatigue from constant evaluation',
                'Relationships can suffer when frugality creates conflict'
            ],
            trendFollower: [
                'Extreme volatility exposure - portfolio can lose 80-90%',
                'Many trending investments turn out to be fads or scams',
                'Vulnerable to hacks, rug pulls, and security risks',
                'Regulatory uncertainty',
                'Echo chamber effect from online communities',
                'FOMO-driven decisions',
                'Difficulty distinguishing innovation from hype',
                'Tax complexity from numerous transactions'
            ]
        },

        // Famous investors
        famousInvestors: {
            safeGuard: [
                { name: 'Benjamin Graham', title: 'Father of Value Investing', quote: 'The essence of investment management is the management of risks, not returns.' },
                { name: 'John Bogle', title: 'Founder of Vanguard', quote: 'Don\'t look for the needle in the haystack. Just buy the haystack.' }
            ],
            aggressiveBull: [
                { name: 'George Soros', title: 'Legendary Hedge Fund Manager', quote: 'It\'s not whether you\'re right or wrong, but how much money you make when you\'re right.' },
                { name: 'Paul Tudor Jones', title: 'Macro Trading Pioneer', quote: 'The secret to being successful is to always be on the lookout for the next big trade.' }
            ],
            strategicPlanner: [
                { name: 'Warren Buffett', title: 'The Oracle of Omaha', quote: 'Our favorite holding period is forever.' },
                { name: 'Ray Dalio', title: 'Founder of Bridgewater', quote: 'Diversifying well is the most important thing you need to do in order to invest well.' }
            ],
            valueSeeker: [
                { name: 'Charlie Munger', title: 'Vice Chairman of Berkshire', quote: 'Spend each day trying to be a little wiser than you were when you woke up.' },
                { name: 'Mr. Money Mustache', title: 'FIRE Movement Pioneer', quote: 'The real measure of your wealth is how much you\'d be worth if you lost all your money.' }
            ],
            trendFollower: [
                { name: 'Cathie Wood', title: 'CEO of ARK Invest', quote: 'We believe innovation is key to growth. We are not afraid to be different.' },
                { name: 'Chamath Palihapitiya', title: 'Tech Investor & SPAC Pioneer', quote: 'The future is going to be built by people who are willing to take risks.' }
            ]
        },

        // Compatibility
        compatibility: {
            safeGuard: {
                best: { type: 'strategicPlanner', reason: 'Strategic Planners help you optimize returns while respecting your risk tolerance' },
                good: { type: 'valueSeeker', reason: 'Value Seekers share your appreciation for financial security and careful spending' },
                challenging: { type: 'aggressiveBull', reason: 'Aggressive Bulls may push you too far outside your comfort zone' }
            },
            aggressiveBull: {
                best: { type: 'trendFollower', reason: 'Trend Followers share your appetite for emerging opportunities and high conviction' },
                good: { type: 'strategicPlanner', reason: 'Strategic Planners can help you add discipline and risk management to your approach' },
                challenging: { type: 'safeGuard', reason: 'Safe Guards may find your risk tolerance anxiety-inducing and reckless' }
            },
            strategicPlanner: {
                best: { type: 'safeGuard', reason: 'Safe Guards appreciate your methodical approach and risk management focus' },
                good: { type: 'valueSeeker', reason: 'Value Seekers align with your emphasis on optimization and efficiency' },
                challenging: { type: 'trendFollower', reason: 'Trend Followers may find your systematic approach too slow and rigid' }
            },
            valueSeeker: {
                best: { type: 'safeGuard', reason: 'Safe Guards share your appreciation for security and avoiding unnecessary risk' },
                good: { type: 'strategicPlanner', reason: 'Strategic Planners appreciate your discipline and optimization mindset' },
                challenging: { type: 'aggressiveBull', reason: 'Aggressive Bulls may view your frugality as limiting potential gains' }
            },
            trendFollower: {
                best: { type: 'aggressiveBull', reason: 'Aggressive Bulls share your high-conviction approach and risk appetite' },
                good: { type: 'strategicPlanner', reason: 'Strategic Planners can help you build a framework around your trend insights' },
                challenging: { type: 'safeGuard', reason: 'Safe Guards may view your investments as too speculative and risky' }
            }
        },

        // Index page (Dictionary)
        dictionary: {
            title: 'Financial Dictionary',
            subtitle: 'Learn 1,400+ financial terms with easy-to-understand definitions and real-world examples',
            todaysTerms: 'Today\'s Terms',
            searchPlaceholder: 'Search terms... (e.g., Inflation, GDP, Bond)',
            definition: 'Definition',
            example: 'Example',
            noResults: 'No terms found',
            tryDifferent: 'Try a different search term'
        }
    },

    ko: {
        // Common
        common: {
            search: '검색',
            all: '전체',
            retakeTest: '테스트 다시하기',
            shareResults: '결과 공유하기',
            shareYourResult: '결과 공유하기',
            shareOnTwitter: 'X (트위터)에 공유',
            shareOnFacebook: '페이스북에 공유',
            copyLink: '링크 복사',
            copied: '복사됨!',
            close: '닫기',
            previous: '이전',
            next: '다음',
            seeResults: '결과 보기',
            startTest: '테스트 시작'
        },

        // Navigation
        nav: {
            dictionary: '경제용어사전',
            personalityTest: '경제성향 테스트',
            salaryCalculator: '연봉 계산기'
        },

        // Personality Test Page
        personalityTest: {
            title: '나의 경제 성향 테스트',
            subtitle: '5개 섹션, 50개의 질문을 통해 나의 경제 성향을 알아보세요.',
            subtitle2: '나의 강점, 약점, 그리고 이상적인 투자 전략을 발견하세요.',
            duration: '8-10분 소요',
            durationDesc: '종합 분석',
            questions: '50개 질문',
            questionsDesc: '5개 섹션, 각 10개',
            personalityTypes: '5가지 성향 유형',
            questionOf: '질문',
            of: '/',
            stronglyDisagree: '매우 아니다',
            stronglyAgree: '매우 그렇다',
            yourEconomicPersonality: '나의 경제 성향',
            basedOnResponses: '응답 결과를 기반으로 한 나의 경제 프로필입니다',
            youAre: '당신은',
            keyTraits: '주요 특성',
            strengths: '강점',
            areasToImprove: '개선이 필요한 부분',
            investorsShareStyle: '같은 스타일의 투자자들',
            compatibilityWithOthers: '다른 유형과의 궁합',
            bestMatch: '최고 궁합',
            goodMatch: '좋은 궁합',
            challenging: '도전적 궁합'
        },

        // Personality Type Names
        types: {
            safeGuard: '안전 수호자',
            aggressiveBull: '공격적인 황소',
            strategicPlanner: '전략적 설계자',
            valueSeeker: '가치 추구자',
            trendFollower: '트렌드 추종자'
        },

        // Slogans
        slogans: {
            safeGuard: '"재산을 지키는 것이 재산을 늘리는 첫걸음이다."',
            aggressiveBull: '"높은 위험, 높은 수익; 나는 시장의 파도를 탄다."',
            strategicPlanner: '"성공은 철저한 계획과 분산투자의 결과이다."',
            valueSeeker: '"티끌 모아 태산; 현명한 소비가 곧 투자다."',
            trendFollower: '"혁신이 부를 이끈다; 나는 오늘, 미래에 투자한다."'
        },

        // Sections
        sections: {
            section1: '섹션 1: 투자 태도 및 위험 성향',
            section2: '섹션 2: 소비 습관 및 가치 추구',
            section3: '섹션 3: 정보 및 기술 트렌드',
            section4: '섹션 4: 계획 및 자산 관리',
            section5: '섹션 5: 라이프스타일 및 철학'
        },

        // Questions
        questions: [
            // Section 1
            '원금 손실 가능성이 1%라도 있으면 투자를 피한다.',
            '"고위험, 고수익"이 내 인생 모토이다.',
            '레버리지(대출)를 활용하는 것은 부자가 되기 위한 필수 전략이라고 생각한다.',
            '투자할 때 가장 먼저 확인하는 것은 "최대 손실 가능 금액"이다.',
            '급등하는 주식이나 뉴스에 나오는 핫한 종목에 끌린다.',
            '암호화폐가 미래의 핵심 자산이 될 것이라고 믿는다.',
            '분산투자보다 믿는 한두 종목에 집중 투자하는 것을 선호한다.',
            '공포에 휩싸인 시장이 가장 좋은 매수 기회라고 생각한다.',
            '복리의 힘을 믿으며 10년 이상 장기 투자할 준비가 되어 있다.',
            '자산의 50% 이상이 현금이어야 마음이 편하다.',
            // Section 2
            '구매 전 최소 3개 이상의 사이트에서 가격을 비교한다.',
            '"가성비"보다 "브랜드 이미지"가 더 중요하다.',
            '항공 마일리지나 캐시백 같은 신용카드 혜택을 최대한 활용한다.',
            '가계부나 앱을 사용하여 모든 지출을 꼼꼼히 기록한다.',
            '친구들에게 "알뜰하다" 또는 "짠돌이/짠순이"라는 말을 들은 적이 있다.',
            '정기적으로 구독 서비스를 검토하고 사용하지 않는 것은 해지한다.',
            '나 자신을 위한 "사치스러운 선물"에는 아낌없이 쓴다.',
            '전기세, 수도세 같은 공과금을 절약하려고 의식적으로 노력한다.',
            '충동구매를 피하기 위해 장바구니에 며칠 두었다가 결제한다.',
            '통장 잔고가 늘어나는 것을 보는 기쁨이 소비의 기쁨보다 크다.',
            // Section 3
            '새로운 금융 앱(네오뱅크, 핀테크)이 나오면 바로 설치해본다.',
            '핀테크, AI, 로보틱스 같은 미래 산업에 관심이 많다.',
            'NFT나 메타버스 토지 같은 디지털 자산의 가치를 인정한다.',
            '새로운 돈 버는 방법을 친구들에게 가장 먼저 알려주는 편이다.',
            '복잡한 약관이나 공시자료를 읽는 것이 싫지 않다.',
            'ChatGPT 같은 도구를 전문적인 투자 분석에 활용하고 싶다.',
            '남들이 아직 모르는 "숨은 보석" 같은 정보를 찾는 데 집착한다.',
            '금리나 환율 뉴스를 항상 내 삶과 연결지어 생각한다.',
            '현재 적자여도 혁신적인 기업은 투자할 가치가 있다고 생각한다.',
            '세상이 빨리 변할수록 돈 벌 기회가 더 많다고 생각한다.',
            // Section 4
            '10년 또는 20년 후 도달하고 싶은 구체적인 자산 목표가 있다.',
            '월급이 들어오면 먼저 저축/투자하고 남은 돈으로 생활한다.',
            '주식, 부동산, 현금 사이에 일정한 비율(포트폴리오)을 유지한다.',
            '예상치 못한 지출을 위해 비상금을 항상 따로 보관한다.',
            '오늘 내 정확한 "순자산"(자산 - 부채)을 숫자로 알고 있다.',
            '대출은 "나쁜 것"이 아니라 전략적 도구라고 생각한다.',
            '은퇴 계획은 가능한 일찍 시작해야 한다고 생각하며, 이미 시작했다.',
            '월별 지출을 카테고리별로 분류한 예산을 따라 생활한다.',
            '수익이 나면 재투자하여 복리의 힘을 극대화한다.',
            '"인내심"이 부자가 되기 위한 가장 중요한 덕목이라고 생각한다.',
            // Section 5
            '돈을 쓰는 것보다 모으는 과정에서 더 행복하다.',
            '돈을 벌기 위해 어느 정도의 시간과 건강을 희생할 의향이 있다.',
            '"경제적 자유"를 달성하는 것이 내 인생의 궁극적인 목표이다.',
            '남들에게 "부자처럼 보이는 것"보다 "실제로 부자인 것"이 더 중요하다.',
            '비싼 차를 모는 것보다 좋은 주식을 소유하는 것이 더 자랑스럽다.',
            '복권 같은 운에 의존하기보다 실력으로 부를 쌓고 싶다.',
            '미니멀리즘(적게 소유하기)은 내 경제관과 잘 맞는다.',
            '엄청난 돈을 위해서라면 사랑이나 우정을 포기할 수도 있다고 생각한다.',
            '경제적으로 크게 성공한 사람들의 전기를 읽는 것을 즐긴다.',
            '현재의 행복을 위해 돈을 쓰는 것(YOLO)도 가치 있다고 생각한다.'
        ],

        // Traits for each type
        traits: {
            safeGuard: [
                '높은 수익보다 원금 보존을 우선시 - 10%를 위해 손실 위험을 감수하기보다 안전하게 3%를 벌겠다',
                '국채, 정기예금, 고금리 저축계좌 같은 보장된 저위험 투자를 선호',
                '6-12개월, 때로는 그 이상의 생활비를 비상금으로 유지',
                '모든 형태의 부채를 철저히 피하고 신용카드 잔액은 매달 즉시 상환',
                '예측 가능성과 안정성을 중시 - 돈이 어디에 있는지 정확히 알 때 마음이 편안함',
                '모든 재정 결정을 철저히 조사하며, 때로는 결정하기까지 수개월이 걸림',
                '추상적인 금융 상품보다 실물 자산과 유형 투자를 선호',
                '개인적 경험이나 가족력에서 비롯된 재정 손실에 대한 깊은 두려움 보유'
            ],
            aggressiveBull: [
                '항상 다음 대박 투자를 찾음 - 10배, 100배 기회를 끊임없이 탐색',
                '극심한 변동성에 편안함 - 일주일에 30% 포트폴리오 변동은 일상',
                '레버리지(마진, 옵션, 선물)를 사용하여 가용 자본 이상의 잠재 수익 극대화',
                '기회에 대해 빠르고 결단력 있는 결정 - 망설임은 기회를 놓치는 것',
                '개인의 능력과 분석으로 시장 평균을 지속적으로 이길 수 있다고 믿음',
                'IPO, 프리마켓 거래, 실적 발표, 모멘텀 거래 같은 고위험 환경에 끌림',
                '손실을 실패가 아닌 시장 교육을 위한 등록금으로 봄',
                '고위험 거래에서 오는 아드레날린과 흥분에서 활력을 얻음'
            ],
            strategicPlanner: [
                '절대 모든 달걀을 한 바구니에 담지 않음 - 여러 자산 클래스에 신중하게 계산된 자산 배분 유지',
                '감정이나 직감이 아닌 데이터, 리서치, 분석을 기반으로 모든 재정 결정',
                '10년, 20년, 30년 이상의 장기 자산 형성에 집중',
                '정기적으로 포트폴리오를 리밸런싱하여 목표 배분 유지 및 위험 노출 관리',
                '세금 효율성을 매우 중시 - 세금 우대 계좌, 손실 상계, 전략적 타이밍 활용',
                '일관된 재투자를 통한 복리 성장의 힘을 이해하고 활용',
                '구체적인 이정표, 목표, 비상 시나리오가 포함된 상세한 재정 계획 수립',
                '개인 재정을 예산, 예측, 성과 검토가 있는 사업 운영처럼 다룸'
            ],
            valueSeeker: [
                '할인, 쿠폰, 캐시백 기회가 있으면 절대 정가를 지불하지 않음',
                '거래 찾기 전문가 - 모든 캐시백 앱, 신용카드 혜택, 로열티 프로그램을 알고 있음',
                '앱, 스프레드시트, 상세한 예산을 사용하여 모든 지출을 꼼꼼히 추적',
                '브랜드보다 품질과 내구성을 중시 - 구매 전 사용당 비용을 조사',
                '작은 일상적 절약이 시간이 지나면 엄청난 부로 복리화된다고 믿음',
                '돈을 절약하는 과정에서 진정한 만족과 기쁨을 느낌',
                '자동차부터 통신비까지 모든 것에서 더 좋은 가격, 조건 협상에 능숙',
                '불필요한 지출을 미래의 부와 자유를 말 그대로 버리는 것으로 봄'
            ],
            trendFollower: [
                'AI, 블록체인, 청정 에너지, 바이오테크, 우주 탐사 같은 미래 기술에 적극 투자',
                '암호화폐, DeFi, NFT, Web3 및 새로운 디지털 자산 클래스에 익숙함',
                '주류 채택 전에 새로운 금융 앱, 네오뱅크, 핀테크 혁신의 얼리 어답터',
                '온라인 그룹의 커뮤니티 정서, 소셜 트레이딩, 집단 지성을 중시',
                '기술과 혁신이 현대 부의 창출의 주요 동력이라고 믿음',
                '신흥 분야의 새로운 개발, 프로토콜, 기회에 대해 끊임없이 학습',
                '검증된 비즈니스 모델이나 수익이 없어도 아이디어와 비전에 투자할 의향이 있음',
                '전통 금융을 구식으로 보고 파괴적 혁신을 불가피하고 투자 가능한 것으로 봄'
            ]
        },

        // Strengths for each type
        strengths: {
            safeGuard: [
                '뛰어난 자본 안전성 - 원금이 사실상 건드릴 수 없음',
                '시장 폭락 시 감정적 안정 - 다른 사람들이 공황에 빠질 때 침착함 유지',
                '강력한 유동성 포지션 - 항상 비상사태에 대비',
                '수십 년에 걸쳐 복리화되는 규율 있는 저축 습관',
                '고위험 사기와 사기로부터 자연스럽게 보호됨',
                '돈에 대한 낮은 스트레스 수준',
                '책임감 있는 부채 관리로 인한 우수한 신용 점수',
                '대부분의 사람들보다 경기 침체를 잘 견딜 수 있는 능력'
            ],
            aggressiveBull: [
                '진정으로 인생을 바꿀 수 있는 수익 가능성',
                '시장 기회에 대한 빠른 반응 시간',
                '시장 메커니즘과 거래 심리에 대한 깊은 이해',
                '일시적 손실에 대한 심리적 회복력',
                '다른 사람들이 두려움에 마비될 때 결단력 있는 행동',
                '고급 금융 상품에 대한 광범위한 지식',
                '비대칭적 위험/보상 기회를 발견하는 자연스러운 능력',
                '포지션에 대한 강한 확신'
            ],
            strategicPlanner: [
                '전략적 분산투자를 통한 최적화된 위험 대비 보상 비율',
                '장기 재정 목표 달성 확률이 가장 높음',
                '세금 효율적인 자산 형성',
                '단일 자산이나 섹터 실패로부터 보호됨',
                '전략이 $1,000에서 $10,000,000까지 원활하게 확장됨',
                '시장 소음으로부터의 감정적 분리',
                '재정 미래에 대한 명확한 가시성',
                '대부분의 투자 결정을 자동화할 수 있는 능력'
            ],
            valueSeeker: [
                '뛰어난 저축률',
                '창의적이고 자원이 풍부한 문제 해결',
                '소비자 부채 함정에 거의 빠지지 않음',
                '경제적 어려움 동안 생존하고 번창할 수 있음',
                '낮은 월 지출은 더 빠른 경제적 독립을 의미',
                '가진 것에 대한 깊은 감사',
                '소비재 제품과 가치에 대한 전문 지식',
                '소비 감소로 인한 환경적 혜택'
            ],
            trendFollower: [
                '일찍 참여하여 대규모 수익 포착 가능',
                '최첨단 금융 도구에 능숙함',
                '온라인 커뮤니티를 통한 고유한 알파에 접근',
                '기술 발전에 맞춰진 포트폴리오',
                '투자가 열정적인 취미처럼 느껴짐',
                '복잡성에 편안함',
                '같은 생각을 가진 투자자들의 강력한 네트워크',
                '빠르게 전환할 준비가 된 적응력 있는 사고방식'
            ]
        },

        // Weaknesses for each type
        weaknesses: {
            safeGuard: [
                '수익률이 종종 인플레이션에 뒤처짐 - 구매력이 감소할 수 있음',
                '상승장에서 놓친 기회들',
                '낮은 이자율의 전통적 은행 상품에 과도한 의존',
                '변화하는 경제 환경에 적응하기 어려움',
                '부가 지수적이 아닌 선형적으로 성장',
                '어떤 재정적 위험에도 과도한 불안을 느낄 수 있음',
                '너무 많은 현금 보유의 기회비용',
                '"작은 것에 현명하고 큰 것에 어리석은" 위험'
            ],
            aggressiveBull: [
                '상당하거나 전체 자본 손실의 높은 위험',
                '시장 변동성이 정신 건강과 인간관계에 영향을 줄 수 있음',
                '높은 거래 수수료와 세금 비효율성',
                '손실 후 감정적 "복수 거래"에 취약',
                '도박과 같은 중독 패턴이 발전할 수 있음',
                '과거 승리로 인한 과신이 미래 손실로 이어질 수 있음',
                '일과 삶의 균형 유지 어려움',
                '생존자 편향 - 손실을 잊으면서 승리를 기억'
            ],
            strategicPlanner: [
                '상당한 초기 연구와 설정 시간 필요',
                '과정이 활성 거래에 비해 지루하게 느껴질 수 있음',
                '분석 마비 - 최적화하면서 행동 지연',
                '상승장에서 집중 포트폴리오보다 저조한 성과를 낼 수 있음',
                '추적 도구에 대한 과도한 의존 - 복잡성이 압도할 수 있음',
                '경직된 준수가 명백한 기회를 놓칠 수 있음',
                '최적화에 집착할 수 있음',
                '금융을 논의할 때 설교하는 것처럼 보일 수 있음'
            ],
            valueSeeker: [
                '거래 찾기에 쓴 시간이 절약한 가치를 초과할 수 있음',
                '더 낮은 가격을 위해 품질을 희생할 수 있음',
                '인색하거나 짠돌이로 인식될 수 있음',
                '결핍 마인드셋이 더 많이 버는 비전을 제한할 수 있음',
                '노동의 결실을 완전히 즐기지 못할 수 있음',
                '주요 재정 기회를 무시하면서 작은 것에 인색함',
                '끊임없는 평가로 인한 결정 피로',
                '절약이 갈등을 만들 때 관계가 어려워질 수 있음'
            ],
            trendFollower: [
                '극심한 변동성 노출 - 포트폴리오가 80-90% 손실될 수 있음',
                '많은 트렌딩 투자가 유행이나 사기로 판명됨',
                '해킹, 러그풀, 보안 위험에 취약',
                '규제 불확실성',
                '온라인 커뮤니티의 에코 챔버 효과',
                'FOMO 주도 결정',
                '혁신과 과대광고를 구별하기 어려움',
                '수많은 거래로 인한 세금 복잡성'
            ]
        },

        // Famous investors
        famousInvestors: {
            safeGuard: [
                { name: '벤저민 그레이엄', title: '가치투자의 아버지', quote: '투자 관리의 본질은 수익 관리가 아니라 위험 관리이다.' },
                { name: '존 보글', title: '뱅가드 창립자', quote: '건초더미에서 바늘을 찾지 마라. 그냥 건초더미를 사라.' }
            ],
            aggressiveBull: [
                { name: '조지 소로스', title: '전설적인 헤지펀드 매니저', quote: '맞고 틀리고가 중요한 게 아니라, 맞았을 때 얼마나 버느냐가 중요하다.' },
                { name: '폴 튜더 존스', title: '매크로 트레이딩의 선구자', quote: '성공의 비결은 항상 다음 큰 거래를 찾는 것이다.' }
            ],
            strategicPlanner: [
                { name: '워렌 버핏', title: '오마하의 현인', quote: '우리가 가장 좋아하는 보유 기간은 영원이다.' },
                { name: '레이 달리오', title: '브릿지워터 창립자', quote: '잘 분산투자하는 것이 잘 투자하기 위해 해야 할 가장 중요한 일이다.' }
            ],
            valueSeeker: [
                { name: '찰리 멍거', title: '버크셔 부회장', quote: '매일 잠에서 깼을 때보다 조금 더 현명해지려고 노력하라.' },
                { name: 'Mr. Money Mustache', title: 'FIRE 운동의 선구자', quote: '당신의 진정한 부의 척도는 모든 돈을 잃었을 때 얼마의 가치가 있느냐이다.' }
            ],
            trendFollower: [
                { name: '캐시 우드', title: 'ARK 인베스트 CEO', quote: '우리는 혁신이 성장의 핵심이라고 믿는다. 우리는 다른 것을 두려워하지 않는다.' },
                { name: '차마스 팔리하피티야', title: '테크 투자자 & SPAC 선구자', quote: '미래는 위험을 감수할 준비가 된 사람들에 의해 만들어질 것이다.' }
            ]
        },

        // Compatibility
        compatibility: {
            safeGuard: {
                best: { type: 'strategicPlanner', reason: '전략적 설계자는 당신의 위험 허용 범위를 존중하면서 수익을 최적화하도록 도와줍니다' },
                good: { type: 'valueSeeker', reason: '가치 추구자는 재정적 안정과 신중한 소비에 대한 당신의 가치관을 공유합니다' },
                challenging: { type: 'aggressiveBull', reason: '공격적인 황소는 당신을 편안한 영역 밖으로 너무 밀어낼 수 있습니다' }
            },
            aggressiveBull: {
                best: { type: 'trendFollower', reason: '트렌드 추종자는 새로운 기회와 높은 확신에 대한 당신의 욕구를 공유합니다' },
                good: { type: 'strategicPlanner', reason: '전략적 설계자는 당신의 접근 방식에 규율과 위험 관리를 추가하도록 도와줄 수 있습니다' },
                challenging: { type: 'safeGuard', reason: '안전 수호자는 당신의 위험 허용 범위를 불안하게 느끼고 무모하다고 생각할 수 있습니다' }
            },
            strategicPlanner: {
                best: { type: 'safeGuard', reason: '안전 수호자는 당신의 체계적인 접근 방식과 위험 관리 초점을 높이 평가합니다' },
                good: { type: 'valueSeeker', reason: '가치 추구자는 당신의 최적화와 효율성에 대한 강조와 일치합니다' },
                challenging: { type: 'trendFollower', reason: '트렌드 추종자는 당신의 체계적인 접근 방식이 너무 느리고 경직되어 있다고 생각할 수 있습니다' }
            },
            valueSeeker: {
                best: { type: 'safeGuard', reason: '안전 수호자는 안전과 불필요한 위험 회피에 대한 당신의 가치관을 공유합니다' },
                good: { type: 'strategicPlanner', reason: '전략적 설계자는 당신의 규율과 최적화 마인드셋을 높이 평가합니다' },
                challenging: { type: 'aggressiveBull', reason: '공격적인 황소는 당신의 절약이 잠재적 이익을 제한한다고 볼 수 있습니다' }
            },
            trendFollower: {
                best: { type: 'aggressiveBull', reason: '공격적인 황소는 당신의 높은 확신 접근 방식과 위험 선호도를 공유합니다' },
                good: { type: 'strategicPlanner', reason: '전략적 설계자는 당신의 트렌드 통찰력을 중심으로 프레임워크를 구축하도록 도와줄 수 있습니다' },
                challenging: { type: 'safeGuard', reason: '안전 수호자는 당신의 투자를 너무 투기적이고 위험하다고 볼 수 있습니다' }
            }
        },

        // Index page (Dictionary)
        dictionary: {
            title: '경제/금융 용어 사전',
            subtitle: '1,400개 이상의 금융 용어를 쉬운 설명과 실제 예시로 배워보세요',
            todaysTerms: '오늘의 용어',
            searchPlaceholder: '용어 검색... (예: 인플레이션, GDP, 채권)',
            definition: '정의',
            example: '예시',
            noResults: '검색 결과가 없습니다',
            tryDifferent: '다른 검색어를 시도해보세요'
        }
    }
};

// Get translation by key path (e.g., 'common.search')
function t(keyPath, lang = null) {
    const language = lang || getCurrentLanguage();
    const keys = keyPath.split('.');
    let value = translations[language];

    for (const key of keys) {
        if (value && value[key] !== undefined) {
            value = value[key];
        } else {
            // Fallback to English
            value = translations.en;
            for (const k of keys) {
                if (value && value[k] !== undefined) {
                    value = value[k];
                } else {
                    return keyPath; // Return key if not found
                }
            }
            break;
        }
    }

    return value;
}

// Get array translation by index
function tArray(keyPath, index, lang = null) {
    const arr = t(keyPath, lang);
    if (Array.isArray(arr) && arr[index] !== undefined) {
        return arr[index];
    }
    return '';
}

// Initialize i18n - call this on page load
function initI18n() {
    // Detect language if not already set
    const storedLang = localStorage.getItem('eh_language');
    if (!storedLang) {
        detectLanguage();
    }
    return getCurrentLanguage();
}

// Export for use in other scripts
window.i18n = {
    detectLanguage,
    getCurrentLanguage,
    setLanguage,
    t,
    tArray,
    translations,
    initI18n
};
