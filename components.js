// Economy Helper - Shared Components

// URL 경로에서 현재 언어 및 맥락 감지
function getURLContext() {
    const path = window.location.pathname;
    const parts = path.split('/');

    let lang = null;
    let isResultsPage = path.includes('/results/');

    // 경로 세그먼트에서 언어 코드 찾기 (뒤에서부터 검색하여 가장 인접한 언어 폴더 탐색)
    for (let i = parts.length - 1; i >= 0; i--) {
        const p = parts[i].toLowerCase();
        if (['kr', 'jp', 'es', 'us', 'pt'].includes(p)) {
            lang = p;
            break;
        }
    }

    const isRoot = (lang === null);

    // 언어 결정: 경로에서 못 찾으면 localStorage나 브라우저 설정 사용
    if (isRoot) {
        const savedLang = localStorage.getItem('preferredLanguage');
        if (savedLang) lang = savedLang;
        else {
            const userLang = (navigator.language || navigator.userLanguage).toLowerCase();
            if (userLang.startsWith('ko')) lang = 'kr';
            else if (userLang.startsWith('ja')) lang = 'jp';
            else if (userLang.startsWith('es')) lang = 'es';
            else if (userLang.startsWith('pt')) lang = 'pt';
            else lang = 'us';
        }
    }

    return {
        lang: lang,
        isRoot: isRoot,
        isResultsPage: isResultsPage
    };
}

const context = getURLContext();
const CURRENT_LANG = context.lang;
const IS_ROOT = context.isRoot;
const IS_RESULTS_PAGE = context.isResultsPage;

const ROOT_PATH = IS_ROOT ? './' : (IS_RESULTS_PAGE ? '../../../' : '../');
const LANG_PATH = IS_ROOT ? `./${CURRENT_LANG}/` : ROOT_PATH + CURRENT_LANG + '/';

// 전역 변수로 노출 (index.html 등에서 사용)
window.CURRENT_LANG = CURRENT_LANG;
window.LANG_PATH = LANG_PATH;
window.IS_ROOT = IS_ROOT;

// 언어별 HTML lang 코드 매핑
const LANG_CODES = {
    kr: 'ko',
    us: 'en',
    jp: 'ja',
    es: 'es',
    pt: 'pt'
};

// 언어별 locale 코드 매핑
const LOCALE_CODES = {
    kr: 'ko_KR',
    us: 'en_US',
    jp: 'ja_JP',
    es: 'es_ES',
    pt: 'pt_BR'
};

// 페이지별 메타 데이터 (다국어)
const PAGE_META = {
    dictionary: {
        kr: {
            title: '경제 도우미 - 누구나 쉽게 배우는 경제 지식',
            description: '투자, 트레이딩, 개인 금융 지식을 마스터하세요. 한국어 서비스를 제공합니다.',
            keywords: '경제 사전, 경제 용어, 경제 용어집, 투자 용어, 거래 용어, 은행 용어, 주식 시장 용어, 개인 금융, 금융 지식',
            siteName: '경제 도우미',
            jsonLd: {
                websiteName: '경제 도우미',
                websiteDesc: '투자, 거래, 은행, 개인 금융 지식을 마스터하세요.',
                termSetName: '경제 사전',
                termSetDesc: '금융 및 경제 용어를 정의와 예시와 함께 포괄적으로 수록한 사전입니다.'
            }
        },
        us: {
            title: 'Economy Helper - Making economics simple for everyone',
            description: 'Master investing, trading, and personal finance knowledge. Explore our tools and calculators.',
            keywords: 'economic terms, finance glossary, investment terms, trading vocabulary, banking terms, stock market terminology, personal finance, financial literacy, compound interest calculator, savings goal',
            siteName: 'Economy Helper',
            jsonLd: {
                websiteName: 'Economy Helper',
                websiteDesc: 'Master investing, trading, and personal finance knowledge.',
                termSetName: 'Economic Dictionary',
                termSetDesc: 'A comprehensive collection of financial and economic terms with definitions and examples.'
            }
        },
        jp: {
            title: '経済ヘルパー - 誰でも簡単に学べる経済知識',
            description: '投資、トレーディング、個人金融の知識をマスターしましょう。日本語サービスを提供しています。',
            keywords: '経済辞典, 経済用語, 投資用語, 取引用語, 銀行用語, 株式市場用語, 個人金融, 金融リテラシー',
            siteName: '経済ヘルパー',
            jsonLd: {
                websiteName: '経済ヘルパー',
                websiteDesc: '投資、取引、銀行、個人金融の知識をマスターしましょう。',
                termSetName: '経済辞典',
                termSetDesc: '金融・経済用語を定義と例を用いて包括的に収録した辞典です。'
            }
        },
        es: {
            title: 'Economy Helper - Haciendo la economía simple para todos',
            description: 'Domina los conocimientos de inversión, trading y finanzas personales. Servicio en español disponible.',
            keywords: 'diccionario económico, términos financieros, vocabulario de inversión, términos de trading, terminología bancaria, mercado de valores, finanzas personales, educación financiera',
            siteName: 'Economy Helper',
            jsonLd: {
                websiteName: 'Economy Helper',
                websiteDesc: 'Domina los conocimientos de inversión, trading y finanzas personales.',
                termSetName: 'Diccionario Económico',
                termSetDesc: 'Una colección completa de términos financieros y económicos con definiciones y ejemplos.'
            }
        },
        pt: {
            title: 'Economy Helper - Tornando a economia simples para todos',
            description: 'Domine conhecimentos de investimento, trading e finanças pessoais. Serviço em português disponível.',
            keywords: 'dicionário econômico, termos financeiros, vocabulário de investimento, termos de trading, terminologia bancária, mercado de ações, finanças pessoais, educação financeira',
            siteName: 'Economy Helper',
            jsonLd: {
                websiteName: 'Economy Helper',
                websiteDesc: 'Domine conhecimentos de investimento, trading e finanças pessoais.',
                termSetName: 'Dicionário Econômico',
                termSetDesc: 'Uma coleção abrangente de termos financeiros e econômicos com definições e exemplos.'
            }
        }
    },
    quiz: {
        kr: {
            title: '경제 퀴즈 - 경제 지식 테스트',
            description: '재미있는 퀴즈로 경제 지식을 테스트해보세요.',
            keywords: '경제 퀴즈, 금융 퀴즈, 경제 테스트, 금융 지식 테스트',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Economy Quiz - Test Your Economic Knowledge',
            description: 'Test your economic knowledge with fun quizzes.',
            keywords: 'economy quiz, finance quiz, economic test, financial literacy test',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '経済クイズ - 経済知識をテスト',
            description: '楽しいクイズで経済知識をテストしましょう。',
            keywords: '経済クイズ, 金融クイズ, 経済テスト, 金融知識テスト',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Quiz de Economía - Pon a prueba tus conocimientos',
            description: 'Pon a prueba tus conocimientos económicos con divertidos cuestionarios.',
            keywords: 'quiz de economía, cuestionario financiero, test económico, prueba de conocimientos financieros',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Quiz de Economia - Teste seus conhecimentos',
            description: 'Teste seus conhecimentos econômicos com questionários divertidos.',
            keywords: 'quiz de economia, questionário financeiro, teste econômico, teste de conhecimentos financeiros',
            siteName: 'Economy Helper'
        }
    },
    'personality-investment': {
        kr: {
            title: '투자 성향 테스트 - 나의 투자 성향 알아보기',
            description: '간단한 테스트로 나의 투자 성향을 알아보세요. 안정형? 공격형? 나에게 맞는 투자 스타일을 찾아보세요.',
            keywords: '투자 성향 테스트, 투자 심리 테스트, 투자 유형 테스트, 투자 스타일, 투자자 유형',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Investment Personality Test - Discover Your Investment Style',
            description: 'Discover your investment personality with our simple test. Are you conservative or aggressive? Find your perfect investment style.',
            keywords: 'investment personality test, investor type test, investment style quiz, risk tolerance test',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '投資性向診断 - あなたの投資スタイルを発見',
            description: '簡単なテストであなたの投資性向を発見しましょう。安定型？積極型？あなたに合った投資スタイルを見つけましょう。',
            keywords: '投資性向診断, 投資タイプテスト, 投資スタイル診断, リスク許容度テスト',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Test de Personalidad de Inversión - Descubre tu estilo',
            description: 'Descubre tu personalidad inversora con nuestro test simple. ¿Eres conservador o agresivo? Encuentra tu estilo de inversión perfecto.',
            keywords: 'test de personalidad inversora, tipo de inversor, estilo de inversión, tolerancia al riesgo',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Teste de Personalidade de Investimento - Descubra seu estilo',
            description: 'Descubra sua personalidade de investimento com nosso teste simples. Você é conservador ou agressivo? Encontre seu estilo de investimento perfeito.',
            keywords: 'teste de personalidade de investimento, tipo de investidor, estilo de investimento, tolerância ao risco',
            siteName: 'Economy Helper'
        }
    },
    'personality-spending': {
        kr: {
            title: '소비 성향 테스트 - 나의 소비 습관 알아보기',
            description: '간단한 테스트로 나의 소비 성향을 알아보세요. 알뜰형? 플렉스형? 나에게 맞는 소비 스타일을 찾아보세요.',
            keywords: '소비 성향 테스트, 소비 습관 테스트, 소비 유형 테스트, 소비 스타일, 지출 유형',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Spending Personality Test - Discover Your Spending Habits',
            description: 'Discover your spending personality with our simple test. Are you a saver or a spender? Find your spending style.',
            keywords: 'spending personality test, spending habits quiz, consumer type test, spending style',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '消費性向診断 - あなたの消費習慣を発見',
            description: '簡単なテストであなたの消費性向を発見しましょう。節約型？浪費型？あなたに合った消費スタイルを見つけましょう。',
            keywords: '消費性向診断, 消費習慣テスト, 消費タイプ診断, 消費スタイル',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Test de Personalidad de Consumo - Descubre tus hábitos',
            description: 'Descubre tu personalidad de consumo con nuestro test simple. ¿Eres ahorrador o gastador? Encuentra tu estilo de consumo.',
            keywords: 'test de personalidad de consumo, hábitos de gasto, tipo de consumidor, estilo de consumo',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Teste de Personalidade de Consumo - Descubra seus hábitos',
            description: 'Descubra sua personalidade de consumo com nosso teste simples. Você é poupador ou gastador? Encontre seu estilo de consumo.',
            keywords: 'teste de personalidade de consumo, hábitos de gasto, tipo de consumidor, estilo de consumo',
            siteName: 'Economy Helper'
        }
    },
    compoundInterest: {
        kr: {
            title: '복리 계산기 - 복리의 마법을 경험하세요',
            description: '복리 계산기로 투자 수익을 계산해보세요. 시간이 지남에 따라 자산이 어떻게 성장하는지 확인하세요.',
            keywords: '복리 계산기, 이자 계산기, 투자 계산기, 복리 이자, 자산 성장',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Compound Interest Calculator - Experience the Magic of Compounding',
            description: 'Calculate your investment returns with our compound interest calculator. See how your wealth grows over time.',
            keywords: 'compound interest calculator, interest calculator, investment calculator, compound growth',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '複利計算機 - 複利の魔法を体験',
            description: '複利計算機で投資収益を計算しましょう。時間とともに資産がどのように成長するか確認できます。',
            keywords: '複利計算機, 利息計算機, 投資計算機, 複利, 資産成長',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Calculadora de Interés Compuesto - Experimenta la magia del interés compuesto',
            description: 'Calcula tus rendimientos de inversión con nuestra calculadora de interés compuesto. Mira cómo crece tu riqueza con el tiempo.',
            keywords: 'calculadora de interés compuesto, calculadora de intereses, calculadora de inversiones, crecimiento compuesto',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Calculadora de Juros Compostos - Experimente a mágica dos juros compostos',
            description: 'Calcule seus retornos de investimento com nossa calculadora de juros compostos. Veja como sua riqueza cresce ao longo do tempo.',
            keywords: 'calculadora de juros compostos, calculadora de juros, calculadora de investimentos, crescimento composto',
            siteName: 'Economy Helper'
        }
    },
    savingsGoal: {
        kr: {
            title: '목돈 마련 계산기 - 저축 목표 달성하기',
            description: '목돈 마련 계산기로 저축 계획을 세워보세요. 원하는 금액을 모으기 위해 얼마나 저축해야 하는지 계산합니다.',
            keywords: '목돈 마련 계산기, 저축 계산기, 저축 목표, 재테크 계산기',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Savings Goal Calculator - Reach Your Financial Goals',
            description: 'Plan your savings with our savings goal calculator. Calculate how much you need to save to reach your target amount.',
            keywords: 'savings goal calculator, savings calculator, financial goal calculator, saving planner',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '貯蓄目標計算機 - 財務目標を達成',
            description: '貯蓄目標計算機で貯蓄計画を立てましょう。目標金額に到達するためにどれくらい貯蓄が必要か計算します。',
            keywords: '貯蓄目標計算機, 貯蓄計算機, 財務目標計算機, 貯蓄プランナー',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Calculadora de Meta de Ahorro - Alcanza tus metas financieras',
            description: 'Planifica tus ahorros con nuestra calculadora de metas de ahorro. Calcula cuánto necesitas ahorrar para alcanzar tu objetivo.',
            keywords: 'calculadora de meta de ahorro, calculadora de ahorros, objetivo financiero, planificador de ahorro',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Calculadora de Meta de Poupança - Alcance suas metas financeiras',
            description: 'Planeje suas economias com nossa calculadora de metas de poupança. Calcule quanto você precisa poupar para atingir seu objetivo.',
            keywords: 'calculadora de meta de poupança, calculadora de poupança, objetivo financeiro, planejador de poupança',
            siteName: 'Economy Helper'
        }
    },
    columns: {
        kr: {
            title: '경제 칼럼 - 미시에서 거시까지',
            description: '실생활에서 바로 적용할 수 있는 경제 지식을 쉽고 재미있게 배워보세요. 미시경제부터 거시경제까지 26가지 핵심 주제를 다룹니다.',
            keywords: '경제 칼럼, 경제 상식, 미시경제, 거시경제, 인플레이션, 금리, 투자, 재테크, 경제 공부',
            siteName: '경제 도우미'
        },
        us: {
            title: 'Economy Columns - From Micro to Macro',
            description: 'Learn practical economic knowledge you can apply in everyday life. We cover 26 key topics from microeconomics to macroeconomics.',
            keywords: 'economy columns, economic literacy, microeconomics, macroeconomics, inflation, interest rates, investment, finance education',
            siteName: 'Economy Helper'
        },
        jp: {
            title: '経済コラム - ミクロからマクロまで',
            description: '日常生活ですぐに活用できる経済知識を楽しく学びましょう。ミクロ経済からマクロ経済まで26の重要テーマを扱います。',
            keywords: '経済コラム, 経済常識, ミクロ経済, マクロ経済, インフレ, 金利, 投資, 財テク, 経済学習',
            siteName: '経済ヘルパー'
        },
        es: {
            title: 'Columnas de Economía - De lo Micro a lo Macro',
            description: 'Aprende conocimientos económicos prácticos que puedes aplicar en la vida diaria. Cubrimos 26 temas clave desde microeconomía hasta macroeconomía.',
            keywords: 'columnas de economía, educación económica, microeconomía, macroeconomía, inflación, tasas de interés, inversión, finanzas',
            siteName: 'Economy Helper'
        },
        pt: {
            title: 'Colunas de Economia - Do Micro ao Macro',
            description: 'Aprenda conhecimentos econômicos práticos que você pode aplicar no dia a dia. Cobrimos 26 temas-chave da microeconomia à macroeconomia.',
            keywords: 'colunas de economia, educação econômica, microeconomia, macroeconomia, inflação, taxas de juros, investimento, finanças',
            siteName: 'Economy Helper'
        }
    }
};

// 다국어 설정
const I18N = {
    kr: {
        DICTIONARY: '경제 사전',
        QUIZ: '경제 퀴즈',
        COLUMNS: '경제 칼럼',
        CALCULATORS: '계산기',
        TEST: '성향 테스트',
        INVESTMENT_TEST: '투자 성향 테스트',
        SPENDING_TEST: '소비 성향 테스트',
        COMPOUND: '복리 계산기',
        SAVINGS: '목돈 마련 계산기',
        SHARE_TITLE: '공유하기',
        COPY_LINK: '링크 복사',
        LINK_COPIED: '복사됨!',
        SHARE_TWITTER: 'X (트위터)',
        SHARE_FACEBOOK: '페이스북',
        SHARE_PREFIX: '투자 성향 테스트 결과, 나는'
    },
    us: {
        DICTIONARY: 'Dictionary',
        QUIZ: 'Economy Quiz',
        COLUMNS: 'Columns',
        CALCULATORS: 'Calculators',
        TEST: 'Personality Test',
        INVESTMENT_TEST: 'Investment Personality Test',
        SPENDING_TEST: 'Spending Personality Test',
        COMPOUND: 'Compound Interest',
        SAVINGS: 'Savings Goal',
        SHARE_TITLE: 'Share',
        COPY_LINK: 'Copy Link',
        LINK_COPIED: 'Copied!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook',
        SHARE_PREFIX: 'My Investment Personality Test Result:'
    },
    jp: {
        DICTIONARY: '経済用語辞典',
        QUIZ: '経済クイズ',
        COLUMNS: '経済コラム',
        CALCULATORS: '計算機',
        TEST: '投資診断',
        INVESTMENT_TEST: '投資性向診断',
        SPENDING_TEST: '消費性向診断',
        COMPOUND: '複利計算機',
        SAVINGS: '貯蓄目標シミュレーション',
        SHARE_TITLE: '共有',
        COPY_LINK: 'リンクをコピー',
        LINK_COPIED: 'コピーしました！',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook',
        SHARE_PREFIX: '投資性向テスト結果、私は'
    },
    es: {
        DICTIONARY: 'Diccionario',
        QUIZ: 'Quiz de Economía',
        COLUMNS: 'Columnas',
        CALCULATORS: 'Calculadoras',
        TEST: 'Test de Personalidad',
        INVESTMENT_TEST: 'Test de Personalidad de Inversión',
        SPENDING_TEST: 'Test de Personalidad de Consumo',
        COMPOUND: 'Interés Compuesto',
        SAVINGS: 'Meta de Ahorro',
        SHARE_TITLE: 'Compartir',
        COPY_LINK: 'Copiar enlace',
        LINK_COPIED: '¡Copiado!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook',
        SHARE_PREFIX: 'Resultado del Test de Personalidad de Inversión:'
    },
    pt: {
        DICTIONARY: 'Dicionário',
        QUIZ: 'Quiz de Economia',
        COLUMNS: 'Colunas',
        CALCULATORS: 'Calculadoras',
        TEST: 'Teste de Personalidade',
        INVESTMENT_TEST: 'Teste de Personalidade de Investimento',
        SPENDING_TEST: 'Teste de Personalidade de Consumo',
        COMPOUND: 'Juros Compostos',
        SAVINGS: 'Meta de Poupança',
        SHARE_TITLE: 'Compartilhar',
        COPY_LINK: 'Copiar link',
        LINK_COPIED: 'Copiado!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook',
        SHARE_PREFIX: 'Resultado do Teste de Personalidade de Investimento:'
    }
};

// 네비게이션 아이템
const NAV_ITEMS = [
    { key: 'DICTIONARY', path: 'index.html' },
    { key: 'QUIZ', path: 'economy-quiz.html' },
    { key: 'COLUMNS', path: 'columns.html' },
    { key: 'TEST', path: 'personality-test.html' },
    { key: 'CALCULATORS', path: '#' }, // Has dropdown
];

// Google AdSense 설정
const ADSENSE_CLIENT_ID = 'ca-pub-9654373024529321';


// 테스트 상태 저장 키
const TEST_STATE_KEY = 'personalityTestState';

// 테스트 상태 저장
function saveTestState(currentQuestion, answers) {
    const type = window.location.pathname.includes('investment') ? 'investment' : 'spending';
    const state = {
        currentQuestion: currentQuestion,
        answers: answers,
        timestamp: Date.now()
    };
    localStorage.setItem(TEST_STATE_KEY + '_' + type, JSON.stringify(state));
}

// 테스트 상태 불러오기
function loadTestState() {
    const type = window.location.pathname.includes('investment') ? 'investment' : 'spending';
    const stateJson = localStorage.getItem(TEST_STATE_KEY + '_' + type);
    if (!stateJson) return null;

    const state = JSON.parse(stateJson);
    // 24시간 이내 상태만 유효
    if (Date.now() - state.timestamp > 24 * 60 * 60 * 1000) {
        clearTestState();
        return null;
    }
    return state;
}

// 테스트 상태 삭제
function clearTestState() {
    localStorage.removeItem(TEST_STATE_KEY + '_investment');
    localStorage.removeItem(TEST_STATE_KEY + '_spending');
    // 하위 호환성을 위해 기존 키도 삭제
    localStorage.removeItem(TEST_STATE_KEY);
}

// 언어 변경 핸들러
function handleLanguageChange(lang, activePageId) {
    localStorage.setItem('preferredLanguage', lang);

    // 루트 페이지인 경우 현재 페이지 새로고침 (URL 변경 없이 언어만 변경)
    if (IS_ROOT) {
        window.location.reload();
        return;
    }

    const langPath = ROOT_PATH + lang + '/';
    let targetUrl;

    // 성향 테스트 페이지인 경우 상태 초기화
    if (activePageId === 'personality-investment' || activePageId === 'personality-spending') {
        clearTestState();
    }

    if (activePageId === 'personality-investment') {
        targetUrl = langPath + 'investment-test.html';
    } else if (activePageId === 'personality-spending') {
        targetUrl = langPath + 'spending-test.html';
    } else if (activePageId === 'compoundInterest') {
        targetUrl = langPath + 'compound-interest-calculator.html';
    } else if (activePageId === 'savingsGoal') {
        targetUrl = langPath + 'savings-goal-calculator.html';
    } else if (activePageId === 'quiz') {
        targetUrl = langPath + 'economy-quiz.html';
    } else if (activePageId === 'columns') {
        targetUrl = langPath + 'columns.html';
    } else if (activePageId === 'columnDetail') {
        // 칼럼 상세 페이지에서 언어 변경 시 동일 칼럼의 다른 언어 버전으로 이동
        const urlParams = new URLSearchParams(window.location.search);
        const columnId = urlParams.get('id');
        targetUrl = langPath + 'column.html' + (columnId ? '?id=' + columnId : '');
    } else {
        targetUrl = langPath + 'index.html';
    }

    window.location.href = targetUrl;
}

// AdSense 스크립트 로드
function injectAdSenseScripts() {
    if (document.getElementById('adsense-script')) return;

    const script = document.createElement('script');
    script.id = 'adsense-script';
    script.async = true;
    script.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${ADSENSE_CLIENT_ID}`;
    script.crossOrigin = 'anonymous';
    document.head.appendChild(script);
}


// 공통 스타일 주입 (정적 로딩으로 전환 - HTML head에서 직접 로드)
function injectCommonStyles() {
    // CSS는 각 HTML 파일의 head에서 정적으로 로드됨
    // 동적 로딩 제거로 FOUC(Flash of Unstyled Content) 방지
}

// 헤더 렌더링
function renderHeader(activePageId) {
    const header = document.getElementById('header');
    if (!header) return;

    const lang = I18N[CURRENT_LANG];
    // Safety check
    if (!lang) {
        console.error('Language not found:', CURRENT_LANG);
        return;
    }

    const langLabels = {
        kr: '한국어',
        us: 'English',
        jp: '日本語',
        es: 'Español',
        pt: 'Português'
    };
    const langLabel = langLabels[CURRENT_LANG];
    const otherLangs = Object.keys(langLabels).filter(l => l !== CURRENT_LANG);

    const isCalculatorActive = activePageId === 'compoundInterest' || activePageId === 'savingsGoal';
    const isTestActive = activePageId === 'personality-investment' || activePageId === 'personality-spending';

    // 네비게이션 아이템 생성 (Desktop)
    const navItemsDesktop = NAV_ITEMS.map(item => {
        if (item.key === 'CALCULATORS') {
            return '<li class="dropdown dropdown-hover dropdown-end"><label tabindex="0" class="font-medium ' + (isCalculatorActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50') + ' rounded-lg cursor-pointer flex items-center gap-1">' + lang.CALCULATORS + '<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg></label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100"><li><a href="' + LANG_PATH + 'compound-interest-calculator.html" class="' + (activePageId === 'compoundInterest' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.COMPOUND + '</a></li><li><a href="' + LANG_PATH + 'savings-goal-calculator.html" class="' + (activePageId === 'savingsGoal' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.SAVINGS + '</a></li></ul></li>';
        }

        if (item.key === 'TEST') {
            return '<li class="dropdown dropdown-hover dropdown-end"><label tabindex="0" class="font-medium ' + (isTestActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50') + ' rounded-lg cursor-pointer flex items-center gap-1">' + lang.TEST + '<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg></label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-64 border border-slate-100"><li><a href="' + LANG_PATH + 'investment-test.html" class="' + (activePageId === 'personality-investment' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.INVESTMENT_TEST + '</a></li><li><a href="' + LANG_PATH + 'spending-test.html" class="' + (activePageId === 'personality-spending' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.SPENDING_TEST + '</a></li></ul></li>';
        }

        // General link
        let isActive = false;
        if (item.key === 'DICTIONARY' && activePageId === 'dictionary') isActive = true;
        if (item.key === 'QUIZ' && activePageId === 'quiz') isActive = true;
        if (item.key === 'COLUMNS' && (activePageId === 'columns' || activePageId === 'columnDetail')) isActive = true;

        const activeClass = isActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        return '<li><a href="' + LANG_PATH + item.path + '" class="font-medium ' + activeClass + ' rounded-lg">' + lang[item.key] + '</a></li>';
    }).join('');

    // 언어 스위치 (Desktop)
    const langOptionsDesktop = otherLangs.map(l =>
        '<li><a onclick="handleLanguageChange(\'' + l + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer">' + langLabels[l] + '</a></li>'
    ).join('');
    const langSwitchDesktop = '<li class="dropdown dropdown-hover dropdown-end ml-2 shrink-0"><label tabindex="0" class="btn btn-ghost btn-sm gap-1 font-medium text-slate-600 hover:text-blue-600 whitespace-nowrap"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabel + '</label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-32 border border-slate-100">' + langOptionsDesktop + '</ul></li>';

    // 네비게이션 아이템 생성 (Mobile)
    const navItemsMobile = NAV_ITEMS.map(item => {
        if (item.key === 'CALCULATORS') {
            return '<li><details><summary class="font-medium ' + (isCalculatorActive ? 'text-blue-600' : 'text-slate-600') + '">' + lang.CALCULATORS + '</summary><ul class="p-2 bg-slate-50 rounded-lg"><li><a href="' + LANG_PATH + 'compound-interest-calculator.html" class="' + (activePageId === 'compoundInterest' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.COMPOUND + '</a></li><li><a href="' + LANG_PATH + 'savings-goal-calculator.html" class="' + (activePageId === 'savingsGoal' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.SAVINGS + '</a></li></ul></details></li>';
        }

        if (item.key === 'TEST') {
            return '<li><details><summary class="font-medium ' + (isTestActive ? 'text-blue-600' : 'text-slate-600') + '">' + lang.TEST + '</summary><ul class="p-2 bg-slate-50 rounded-lg"><li><a href="' + LANG_PATH + 'investment-test.html" class="' + (activePageId === 'personality-investment' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.INVESTMENT_TEST + '</a></li><li><a href="' + LANG_PATH + 'spending-test.html" class="' + (activePageId === 'personality-spending' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.SPENDING_TEST + '</a></li></ul></details></li>';
        }

        let isActive = false;
        if (item.key === 'DICTIONARY' && activePageId === 'dictionary') isActive = true;
        if (item.key === 'QUIZ' && activePageId === 'quiz') isActive = true;
        if (item.key === 'COLUMNS' && (activePageId === 'columns' || activePageId === 'columnDetail')) isActive = true;

        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return '<li><a href="' + LANG_PATH + item.path + '" class="font-medium ' + activeClass + '">' + lang[item.key] + '</a></li>';
    }).join('');

    // 언어 스위치 (Mobile)
    const langOptionsMobile = otherLangs.map(l =>
        '<li><a onclick="handleLanguageChange(\'' + l + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer flex items-center gap-2"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabels[l] + '</a></li>'
    ).join('');
    const langSwitchMobile = '<li class="border-t border-slate-100 mt-2 pt-2">' + langOptionsMobile + '</li>';

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start shrink-0"><a href="' + ROOT_PATH + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + ROOT_PATH + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1 items-center flex-nowrap">' + navItemsDesktop + langSwitchDesktop + '</ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + langSwitchMobile + '</ul></div></div></div></div>';
}

// 푸터 렌더링
function renderFooter() {
    const footer = document.getElementById('footer');
    if (!footer) return;
    footer.innerHTML = '<div class="container mx-auto px-4 py-6"><p class="text-center text-slate-500 text-sm">&copy; ' + new Date().getFullYear() + ' Economy Helper. All rights reserved.</p></div>';
}

// Head 초기화 (html lang, meta tags, hreflang, JSON-LD)
function initHead(activePageId) {
    const lang = CURRENT_LANG;
    const htmlLang = LANG_CODES[lang] || 'en';
    const locale = LOCALE_CODES[lang] || 'en_US';

    // HTML lang 속성 설정
    document.documentElement.lang = htmlLang;

    // 페이지 메타 데이터 가져오기
    const pageMeta = PAGE_META[activePageId]?.[lang] || PAGE_META.dictionary?.[lang] || PAGE_META.dictionary.us;

    // Title 설정
    document.title = pageMeta.title;

    // 기존 동적 메타 태그 제거 (중복 방지)
    document.querySelectorAll('meta[data-dynamic]').forEach(el => el.remove());
    document.querySelectorAll('link[data-dynamic]').forEach(el => el.remove());
    document.querySelectorAll('script[data-dynamic]').forEach(el => el.remove());

    // 메타 태그 헬퍼 함수
    function setMeta(name, content, isProperty = false) {
        const attr = isProperty ? 'property' : 'name';
        let meta = document.querySelector(`meta[${attr}="${name}"]`);
        if (!meta) {
            meta = document.createElement('meta');
            meta.setAttribute(attr, name);
            meta.setAttribute('data-dynamic', 'true');
            document.head.appendChild(meta);
        }
        meta.content = content;
    }

    // 기본 URL 생성
    const baseUrl = 'https://economyhelper.com';
    const langPaths = { kr: '/kr/', us: '/us/', jp: '/jp/', es: '/es/', pt: '/pt/' };
    const pageFiles = {
        dictionary: 'index.html',
        quiz: 'economy-quiz.html',
        'personality-investment': 'investment-test.html',
        'personality-spending': 'spending-test.html',
        compoundInterest: 'compound-interest-calculator.html',
        savingsGoal: 'savings-goal-calculator.html'
    };
    const pageFile = pageFiles[activePageId] || 'index.html';
    const currentUrl = baseUrl + langPaths[lang] + (pageFile === 'index.html' ? '' : pageFile);

    // Primary Meta Tags
    setMeta('title', pageMeta.title);
    setMeta('description', pageMeta.description);
    setMeta('keywords', pageMeta.keywords);
    setMeta('author', pageMeta.siteName);
    setMeta('robots', 'index, follow');

    // Open Graph
    setMeta('og:type', 'website', true);
    setMeta('og:url', currentUrl, true);
    setMeta('og:title', pageMeta.title, true);
    setMeta('og:description', pageMeta.description, true);
    setMeta('og:image', baseUrl + '/images/og.png', true);
    setMeta('og:image:width', '1200', true);
    setMeta('og:image:height', '630', true);
    setMeta('og:site_name', pageMeta.siteName, true);
    setMeta('og:locale', locale, true);

    // Twitter
    setMeta('twitter:card', 'summary_large_image');
    setMeta('twitter:url', currentUrl);
    setMeta('twitter:title', pageMeta.title);
    setMeta('twitter:description', pageMeta.description);
    setMeta('twitter:image', baseUrl + '/images/og.png');

    // Additional SEO
    setMeta('theme-color', '#3B82F6');
    setMeta('apple-mobile-web-app-title', pageMeta.siteName);
    setMeta('application-name', pageMeta.siteName);

    // Canonical URL
    let canonical = document.querySelector('link[rel="canonical"]');
    if (!canonical) {
        canonical = document.createElement('link');
        canonical.rel = 'canonical';
        canonical.setAttribute('data-dynamic', 'true');
        document.head.appendChild(canonical);
    }
    canonical.href = currentUrl;

    // Hreflang 태그들
    const hreflangMap = { us: 'en', kr: 'ko', jp: 'ja', es: 'es', pt: 'pt' };

    // x-default (영어)
    let xDefault = document.querySelector('link[hreflang="x-default"]');
    if (!xDefault) {
        xDefault = document.createElement('link');
        xDefault.rel = 'alternate';
        xDefault.setAttribute('hreflang', 'x-default');
        xDefault.setAttribute('data-dynamic', 'true');
        document.head.appendChild(xDefault);
    }
    xDefault.href = baseUrl + '/us/' + (pageFile === 'index.html' ? '' : pageFile);

    // 각 언어별 hreflang
    Object.entries(hreflangMap).forEach(([langKey, hreflang]) => {
        let link = document.querySelector(`link[hreflang="${hreflang}"]`);
        if (!link) {
            link = document.createElement('link');
            link.rel = 'alternate';
            link.setAttribute('hreflang', hreflang);
            link.setAttribute('data-dynamic', 'true');
            document.head.appendChild(link);
        }
        link.href = baseUrl + langPaths[langKey] + (pageFile === 'index.html' ? '' : pageFile);
    });

    // Organization schema (모든 페이지에 적용 - 구글 서치 콘솔 로고 인식용)
    const orgScript = document.createElement('script');
    orgScript.type = 'application/ld+json';
    orgScript.setAttribute('data-dynamic', 'true');
    orgScript.textContent = JSON.stringify({
        "@context": "https://schema.org",
        "@type": "Organization",
        "name": "Economy Helper",
        "url": baseUrl + "/",
        "logo": {
            "@type": "ImageObject",
            "url": baseUrl + "/images/logo.png",
            "width": 512,
            "height": 512
        },
        "sameAs": []
    });
    document.head.appendChild(orgScript);

    // JSON-LD (dictionary 페이지만)
    if (activePageId === 'dictionary' && pageMeta.jsonLd) {
        const jsonLd = pageMeta.jsonLd;

        // WebSite schema
        const websiteScript = document.createElement('script');
        websiteScript.type = 'application/ld+json';
        websiteScript.setAttribute('data-dynamic', 'true');
        websiteScript.textContent = JSON.stringify({
            "@context": "https://schema.org",
            "@type": "WebSite",
            "name": jsonLd.websiteName,
            "description": jsonLd.websiteDesc,
            "url": baseUrl + "/",
            "potentialAction": {
                "@type": "SearchAction",
                "target": baseUrl + "/?q={search_term_string}",
                "query-input": "required name=search_term_string"
            }
        });
        document.head.appendChild(websiteScript);

        // DefinedTermSet schema
        const termSetScript = document.createElement('script');
        termSetScript.type = 'application/ld+json';
        termSetScript.setAttribute('data-dynamic', 'true');
        termSetScript.textContent = JSON.stringify({
            "@context": "https://schema.org",
            "@type": "DefinedTermSet",
            "name": jsonLd.termSetName,
            "description": jsonLd.termSetDesc,
            "url": baseUrl + "/",
            "inLanguage": htmlLang
        });
        document.head.appendChild(termSetScript);
    }
}

// 컴포넌트 초기화
function initComponents(activePageId) {
    initHead(activePageId);
    injectCommonStyles();
    injectAdSenseScripts();
    renderHeader(activePageId);
    renderFooter();
}

// === 결과 페이지 공유 기능 ===

// 공유 텍스트 생성 (각 페이지에서 getPersonalityName, getPersonalitySlogan 함수 정의 필요)
function getShareText() {
    const name = typeof getPersonalityName === 'function' ? getPersonalityName() : '';
    const slogan = typeof getPersonalitySlogan === 'function' ? getPersonalitySlogan() : '';
    const lang = I18N[CURRENT_LANG] || I18N.us;
    return `${lang.SHARE_PREFIX} "${name}"! ${slogan}`;
}

// 공유 모달 열기
function shareResults() {
    const shareText = getShareText();
    if (navigator.share) {
        navigator.share({
            title: typeof getPersonalityName === 'function' ? getPersonalityName() : '',
            text: shareText,
            url: window.location.href
        });
    } else {
        const copyText = document.getElementById('copy-link-text');
        const lang = I18N[CURRENT_LANG] || I18N.us;
        if (copyText) copyText.textContent = lang.COPY_LINK;
        const modal = document.getElementById('share-modal');
        if (modal) modal.showModal();
    }
}

// X(트위터) 공유
function shareToTwitter() {
    const shareText = getShareText();
    const twitterUrl = `https://twitter.com/intent/tweet?text=${encodeURIComponent(shareText)}&url=${encodeURIComponent(window.location.href)}`;
    window.open(twitterUrl, '_blank', 'width=550,height=420');
}

// 페이스북 공유
function shareToFacebook() {
    const facebookUrl = `https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(window.location.href)}`;
    window.open(facebookUrl, '_blank', 'width=550,height=420');
}

// 링크 복사
function copyShareLink() {
    const shareText = getShareText();
    const fullText = `${shareText}\n\n${window.location.href}`;
    const copyTextEl = document.getElementById('copy-link-text');
    const lang = I18N[CURRENT_LANG] || I18N.us;

    navigator.clipboard.writeText(fullText).then(() => {
        if (copyTextEl) {
            copyTextEl.textContent = lang.LINK_COPIED;
            setTimeout(() => { copyTextEl.textContent = lang.COPY_LINK; }, 2000);
        }
    });
}
