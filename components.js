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

// 다국어 설정
const I18N = {
    kr: {
        DICTIONARY: '경제 사전',
        QUIZ: '경제 퀴즈',
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

// 언어 변경 시 localStorage에 저장
function switchLanguage(targetLang, targetUrl) {
    localStorage.setItem('preferredLanguage', targetLang);
    window.location.href = targetUrl;
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


// 검색 엔진 로봇 여부 확인
function isSearchBot() {
    const botUserAgents = [
        'googlebot', 'bingbot', 'yandexbot', 'baiduspider', 'twitterbot', 'facebookexternalhit', 'rogerbot', 'linkedinbot', 'embedly', 'quora link preview', 'showyoubot', 'outbrain', 'pinterest/0.', 'developers.google.com/+/web/snippet', 'slackbot', 'vkshare', 'w3c_validator', 'redditbot', 'applebot', 'whatsapp', 'flipboard', 'tumblr', 'bitlybot', 'skypeuripreview', 'nuzzel', 'discordbot', 'google pagead', 'msnbot', 'ia_archiver'
    ];
    const ua = navigator.userAgent.toLowerCase();
    return botUserAgents.some(bot => ua.includes(bot));
}

// 자동 언어 리다이렉션 (검색 봇 제외)
function handleAutoRedirect() {
    if (isSearchBot()) return;

    // 루트 페이지(/ 또는 /index.html)에서는 리다이렉션 안 함 (동적 콘텐츠 노출)
    if (IS_ROOT) return;

    const savedLang = localStorage.getItem('preferredLanguage');
    const path = window.location.pathname;

    // 언어 경로가 아닌 곳에서만 체크 (이미 /kr/ 등에 있으면 안 함)
    if (path === '/' || path.endsWith('/index.html')) {
        // 이미 언어 경로에 있는 경우(예: /kr/index.html) 리다이렉션 안함
        if (path.includes('/kr/') || path.includes('/us/') || path.includes('/jp/') || path.includes('/es/') || path.includes('/pt/')) return;

        // 여기에 도달했다면 리다이렉션 로직이 필요할 수 있으나, IS_ROOT가 true면 위에서 이미 return됨
    }
}

// 공통 스타일 주입
function injectCommonStyles() {
    const stylesheets = [
        { href: 'https://cdn.jsdelivr.net/npm/daisyui@4.4.19/dist/full.min.css' },
        { href: 'https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap' },
        { href: ROOT_PATH + 'styles.css' }
    ];

    stylesheets.forEach(item => {
        if (!document.querySelector('link[href="' + item.href + '"]')) {
            const link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = item.href;
            link.type = 'text/css';
            document.head.appendChild(link);
        }
    });

    if (!document.querySelector('script[src*="tailwindcss.com"]')) {
        const tailwindScript = document.createElement('script');
        tailwindScript.src = 'https://cdn.tailwindcss.com';
        document.head.appendChild(tailwindScript);
    }
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

        const activeClass = isActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        return '<li><a href="' + LANG_PATH + item.path + '" class="font-medium ' + activeClass + ' rounded-lg">' + lang[item.key] + '</a></li>';
    }).join('');

    // 언어 스위치 (Desktop)
    const langOptionsDesktop = otherLangs.map(l =>
        '<li><a onclick="handleLanguageChange(\'' + l + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer">' + langLabels[l] + '</a></li>'
    ).join('');
    const langSwitchDesktop = '<li class="dropdown dropdown-hover dropdown-end ml-2"><label tabindex="0" class="btn btn-ghost btn-sm gap-1 font-medium text-slate-600 hover:text-blue-600"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabel + '</label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-32 border border-slate-100">' + langOptionsDesktop + '</ul></li>';

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

        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return '<li><a href="' + LANG_PATH + item.path + '" class="font-medium ' + activeClass + '">' + lang[item.key] + '</a></li>';
    }).join('');

    // 언어 스위치 (Mobile)
    const langOptionsMobile = otherLangs.map(l =>
        '<li><a onclick="handleLanguageChange(\'' + l + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer flex items-center gap-2"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabels[l] + '</a></li>'
    ).join('');
    const langSwitchMobile = '<li class="border-t border-slate-100 mt-2 pt-2">' + langOptionsMobile + '</li>';

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start"><a href="' + ROOT_PATH + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + ROOT_PATH + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1 items-center">' + navItemsDesktop + langSwitchDesktop + '</ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + langSwitchMobile + '</ul></div></div></div></div>';
}

// 푸터 렌더링
function renderFooter() {
    const footer = document.getElementById('footer');
    if (!footer) return;
    footer.innerHTML = '<div class="container mx-auto px-4 py-6"><p class="text-center text-slate-500 text-sm">&copy; ' + new Date().getFullYear() + ' Economy Helper. All rights reserved.</p></div>';
}

// 컴포넌트 초기화
function initComponents(activePageId) {
    injectCommonStyles();
    injectAdSenseScripts();
    renderHeader(activePageId);
    renderFooter();
    handleAutoRedirect();
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
