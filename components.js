// Economy Helper - Shared Components

// URL 경로에서 현재 언어 및 깊이 감지
const CURRENT_LANG = window.location.pathname.includes('/kr/') ? 'kr' : window.location.pathname.includes('/jp/') ? 'jp' : window.location.pathname.includes('/es/') ? 'es' : 'us';
const IS_RESULTS_PAGE = window.location.pathname.includes('/results/');
const ROOT_PATH = IS_RESULTS_PAGE ? '../../' : '../';
const LANG_PATH = ROOT_PATH + CURRENT_LANG + '/';

// 다국어 설정
const I18N = {
    kr: {
        DICTIONARY: '경제 사전',
        QUIZ: '경제 퀴즈',
        CALCULATORS: '금융 계산기',
        TEST: '투자 성향 테스트',
        COMPOUND: '공학용 복리 계산기',
        SAVINGS: '목돈 마련 계산기',
        SHARE_TITLE: '공유하기',
        COPY_LINK: '링크 복사',
        LINK_COPIED: '링크 복사됨!',
        SHARE_TWITTER: '엑스(트위터)',
        SHARE_FACEBOOK: '페이스북'
    },
    us: {
        DICTIONARY: 'Dictionary',
        QUIZ: 'Economy Quiz',
        CALCULATORS: 'Calculators',
        TEST: 'Personality Test',
        COMPOUND: 'Compound Interest',
        SAVINGS: 'Savings Goal',
        SHARE_TITLE: 'Share',
        COPY_LINK: 'Copy Link',
        LINK_COPIED: 'Copied!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook'
    },
    jp: {
        DICTIONARY: '経済用語辞典',
        QUIZ: '経済クイズ',
        CALCULATORS: '計算機',
        TEST: '投資診断',
        COMPOUND: '複利計算機',
        SAVINGS: '貯蓄目標計算',
        SHARE_TITLE: '共有',
        COPY_LINK: 'リンクをコピー',
        LINK_COPIED: 'コピー完了!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook'
    },
    es: {
        DICTIONARY: 'Diccionario',
        QUIZ: 'Quiz de Economía',
        CALCULATORS: 'Calculadoras',
        TEST: 'Test de Personalidad',
        COMPOUND: 'Interés Compuesto',
        SAVINGS: 'Meta de Ahorro',
        SHARE_TITLE: 'Compartir',
        COPY_LINK: 'Copiar enlace',
        LINK_COPIED: '¡Copiado!',
        SHARE_TWITTER: 'X (Twitter)',
        SHARE_FACEBOOK: 'Facebook'
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
// Google AdSense 설정
const ADSENSE_CLIENT_ID = 'ca-pub-9654373024529321';


// 테스트 상태 저장 키
const TEST_STATE_KEY = 'personalityTestState';

// 테스트 상태 저장
function saveTestState(currentQuestion, answers) {
    const state = {
        currentQuestion: currentQuestion,
        answers: answers,
        timestamp: Date.now()
    };
    localStorage.setItem(TEST_STATE_KEY, JSON.stringify(state));
}

// 테스트 상태 불러오기
function loadTestState() {
    const stateJson = localStorage.getItem(TEST_STATE_KEY);
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
    localStorage.removeItem(TEST_STATE_KEY);
}

// 언어 변경 시 localStorage에 저장
function switchLanguage(targetLang, targetUrl) {
    localStorage.setItem('preferredLanguage', targetLang);
    window.location.href = targetUrl;
}

// 언어 변경 핸들러
function handleLanguageChange(lang, activePageId) {
    const langPath = ROOT_PATH + lang + '/';
    let targetUrl;

    if (activePageId === 'personality' || IS_RESULTS_PAGE) {
        clearTestState();
        targetUrl = langPath + 'personality-test.html';
    } else if (activePageId === 'compoundInterest') {
        targetUrl = langPath + 'compound-interest-calculator.html';
    } else if (activePageId === 'savingsGoal') {
        targetUrl = langPath + 'savings-goal-calculator.html';
    } else if (activePageId === 'quiz') {
        targetUrl = langPath + 'economy-quiz.html';
    } else {
        targetUrl = langPath + 'index.html';
    }

    switchLanguage(lang, targetUrl);
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

// 자동 광고 삽입
function injectAmpAutoAds() {
    if (document.querySelector('amp-auto-ads')) return;

    const ampAutoAds = document.createElement('amp-auto-ads');
    ampAutoAds.setAttribute('type', 'adsense');
    ampAutoAds.setAttribute('data-ad-client', ADSENSE_CLIENT_ID);

    // 모바일 헤더 아래, 데스크탑은 본문 상단 등 적절한 위치
    // 여기서는 body 바로 아래에 삽입하여 자동 배치 유도
    const header = document.getElementById('header');
    if (header && header.nextSibling) {
        header.parentNode.insertBefore(ampAutoAds, header.nextSibling);
    } else {
        document.body.insertBefore(ampAutoAds, document.body.firstChild);
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
        es: 'Español'
    };
    const langLabel = langLabels[CURRENT_LANG];
    const otherLangs = Object.keys(langLabels).filter(l => l !== CURRENT_LANG);

    const isCalculatorActive = activePageId === 'compoundInterest' || activePageId === 'savingsGoal';

    // 네비게이션 아이템 생성 (Desktop)
    const navItemsDesktop = NAV_ITEMS.map(item => {
        if (item.key === 'CALCULATORS') {
            return '<li class="dropdown dropdown-hover dropdown-end"><label tabindex="0" class="font-medium ' + (isCalculatorActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50') + ' rounded-lg cursor-pointer flex items-center gap-1">' + lang.CALCULATORS + '<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg></label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100"><li><a href="' + LANG_PATH + 'compound-interest-calculator.html" class="' + (activePageId === 'compoundInterest' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.COMPOUND + '</a></li><li><a href="' + LANG_PATH + 'savings-goal-calculator.html" class="' + (activePageId === 'savingsGoal' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.SAVINGS + '</a></li></ul></li>';
        }

        // General link
        // Check if item matches active page. For Quiz, activePageId might be 'quiz'.
        // We need to map activePageId to item key somewhat, or just check IDs.
        // Let's rely on string inclusion or exact match if possible, or just passed ID.
        // The pages call initComponents('dictionary'), 'quiz', etc.

        let isActive = false;
        if (item.key === 'DICTIONARY' && activePageId === 'dictionary') isActive = true;
        if (item.key === 'QUIZ' && activePageId === 'quiz') isActive = true;
        if (item.key === 'TEST' && activePageId === 'personality') isActive = true;

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

        let isActive = false;
        if (item.key === 'DICTIONARY' && activePageId === 'dictionary') isActive = true;
        if (item.key === 'QUIZ' && activePageId === 'quiz') isActive = true;
        if (item.key === 'TEST' && activePageId === 'personality') isActive = true;

        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return '<li><a href="' + LANG_PATH + item.path + '" class="font-medium ' + activeClass + '">' + lang[item.key] + '</a></li>';
    }).join('');

    // 언어 스위치 (Mobile)
    const langOptionsMobile = otherLangs.map(l =>
        '<li><a onclick="handleLanguageChange(\'' + l + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer flex items-center gap-2"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabels[l] + '</a></li>'
    ).join('');
    const langSwitchMobile = '<li class="border-t border-slate-100 mt-2 pt-2">' + langOptionsMobile + '</li>';

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start"><a href="' + LANG_PATH + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + ROOT_PATH + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1 items-center">' + navItemsDesktop + langSwitchDesktop + '</ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + langSwitchMobile + '</ul></div></div></div></div>';
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
    injectAmpAutoAds();
    renderHeader(activePageId);
    renderFooter();
}

// === 결과 페이지 공유 기능 ===

// 공유 텍스트 생성 (각 페이지에서 getPersonalityName, getPersonalitySlogan 함수 정의 필요)
function getShareText() {
    const name = typeof getPersonalityName === 'function' ? getPersonalityName() : '';
    const slogan = typeof getPersonalitySlogan === 'function' ? getPersonalitySlogan() : '';
    const prefixes = {
        kr: '투자 성향 테스트 결과, 나는',
        us: 'My Investment Personality Test Result:',
        jp: '投資性向テスト結果、私は',
        es: 'Resultado del Test de Personalidad de Inversión:'
    };
    const prefix = prefixes[CURRENT_LANG] || prefixes.us;
    return `${prefix} "${name}"! ${slogan}`;
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
        const copyLinkTexts = {
            kr: '링크 복사',
            us: 'Copy Link',
            jp: 'リンクをコピー'
        };
        if (copyText) copyText.textContent = copyLinkTexts[CURRENT_LANG] || copyLinkTexts.us;
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
    const copiedTexts = {
        kr: '복사됨!',
        us: 'Copied!',
        jp: 'コピーしました！',
        es: '¡Copiado!'
    };
    const defaultTexts = {
        kr: '링크 복사',
        us: 'Copy Link',
        jp: 'リンクをコピー',
        es: 'Copiar enlace'
    };
    const copiedText = copiedTexts[CURRENT_LANG] || copiedTexts.us;
    const defaultText = defaultTexts[CURRENT_LANG] || defaultTexts.us;

    navigator.clipboard.writeText(fullText).then(() => {
        if (copyTextEl) {
            copyTextEl.textContent = copiedText;
            setTimeout(() => { copyTextEl.textContent = defaultText; }, 2000);
        }
    });
}
