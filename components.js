// Economy Helper - Shared Components

// URL 경로에서 현재 언어 및 깊이 감지
const CURRENT_LANG = window.location.pathname.includes('/kr/') ? 'kr' : 'us';
const IS_RESULTS_PAGE = window.location.pathname.includes('/results/');
const ROOT_PATH = IS_RESULTS_PAGE ? '../../' : '../';
const LANG_PATH = ROOT_PATH + CURRENT_LANG + '/';

// 다국어 설정
const I18N = {
    kr: {
        nav: {
            dictionary: '경제 사전',
            personality: '성향 테스트',
            calculator: '계산기',
            compoundInterest: '복리 계산기',
            savingsGoal: '저축 목표 계산기',
            salaryCalculator: '연봉 계산기'
        }
    },
    us: {
        nav: {
            dictionary: 'Dictionary',
            personality: 'Personality Test',
            calculator: 'Calculator',
            compoundInterest: 'Compound Interest',
            savingsGoal: 'Savings Goal'
        }
    }
};

// 네비게이션 아이템
const NAV_ITEMS = [
    { id: 'dictionary', href: 'index.html' },
    { id: 'personality', href: 'personality-test.html' }
];

// Google AdSense 설정
const ADSENSE_CONFIG = {
    clientId: 'ca-pub-1927828313220344',
    adsbygoogleScript: 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js',
    ampAutoAdsScript: 'https://cdn.ampproject.org/v0/amp-auto-ads-0.1.js'
};

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

    if (activePageId === 'personality') {
        targetUrl = langPath + 'personality-test.html';
    } else if (activePageId === 'compoundInterest') {
        targetUrl = langPath + 'compound-interest-calculator.html';
    } else if (activePageId === 'savingsGoal') {
        targetUrl = langPath + 'savings-goal-calculator.html';
    } else if (activePageId === 'salaryCalculator') {
        // 연봉 계산기는 한국어만 있으므로 영어로 전환시 index로 이동
        targetUrl = langPath + 'index.html';
    } else {
        targetUrl = langPath + 'index.html';
    }

    switchLanguage(lang, targetUrl);
}

// AdSense 스크립트 삽입
function injectAdSenseScripts() {
    if (document.querySelector('script[src*="pagead2.googlesyndication.com"]')) return;

    const adsbyGoogleScript = document.createElement('script');
    adsbyGoogleScript.async = true;
    adsbyGoogleScript.src = ADSENSE_CONFIG.adsbygoogleScript + '?client=' + ADSENSE_CONFIG.clientId;
    adsbyGoogleScript.crossOrigin = 'anonymous';
    document.head.appendChild(adsbyGoogleScript);

    const ampAutoAdsScript = document.createElement('script');
    ampAutoAdsScript.async = true;
    ampAutoAdsScript.setAttribute('custom-element', 'amp-auto-ads');
    ampAutoAdsScript.src = ADSENSE_CONFIG.ampAutoAdsScript;
    document.head.appendChild(ampAutoAdsScript);
}

// AMP Auto Ads 삽입
function injectAmpAutoAds() {
    if (document.querySelector('amp-auto-ads')) return;

    const ampAutoAds = document.createElement('amp-auto-ads');
    ampAutoAds.setAttribute('type', 'adsense');
    ampAutoAds.setAttribute('data-ad-client', ADSENSE_CONFIG.clientId);

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
    const otherLang = CURRENT_LANG === 'kr' ? 'us' : 'kr';
    const langLabel = CURRENT_LANG === 'kr' ? '한국어' : 'English';
    const otherLangLabel = CURRENT_LANG === 'kr' ? 'English' : '한국어';

    // 계산기 드롭다운 활성화 여부
    const isCalculatorActive = activePageId === 'compoundInterest' || activePageId === 'savingsGoal' || activePageId === 'salaryCalculator';

    // 네비게이션 아이템 생성 (Desktop)
    const navItemsDesktop = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        const label = lang.nav[item.id];
        return '<li><a href="' + LANG_PATH + item.href + '" class="font-medium ' + activeClass + ' rounded-lg">' + label + '</a></li>';
    }).join('');

    // 계산기 드롭다운 (Desktop) - 한국어일 때 연봉 계산기 추가
    const salaryCalcItemDesktop = CURRENT_LANG === 'kr' ? '<li><a href="' + LANG_PATH + 'salary-calculator.html" class="' + (activePageId === 'salaryCalculator' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.salaryCalculator + '</a></li>' : '';
    const calculatorDropdownDesktop = '<li class="dropdown dropdown-hover dropdown-end"><label tabindex="0" class="font-medium ' + (isCalculatorActive ? 'text-blue-600 bg-blue-50' : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50') + ' rounded-lg cursor-pointer flex items-center gap-1">' + lang.nav.calculator + '<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg></label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + salaryCalcItemDesktop + '<li><a href="' + LANG_PATH + 'compound-interest-calculator.html" class="' + (activePageId === 'compoundInterest' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.compoundInterest + '</a></li><li><a href="' + LANG_PATH + 'savings-goal-calculator.html" class="' + (activePageId === 'savingsGoal' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.savingsGoal + '</a></li></ul></li>';

    // 언어 스위치 (Desktop)
    const langSwitchDesktop = '<li class="dropdown dropdown-hover dropdown-end ml-2"><label tabindex="0" class="btn btn-ghost btn-sm gap-1 font-medium text-slate-600 hover:text-blue-600"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + langLabel + '</label><ul tabindex="0" class="dropdown-content z-[1] menu p-2 shadow-lg bg-white rounded-xl w-32 border border-slate-100"><li><a onclick="handleLanguageChange(\'' + otherLang + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer">' + otherLangLabel + '</a></li></ul></li>';

    // 네비게이션 아이템 생성 (Mobile)
    const navItemsMobile = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        const label = lang.nav[item.id];
        return '<li><a href="' + LANG_PATH + item.href + '" class="font-medium ' + activeClass + '">' + label + '</a></li>';
    }).join('');

    // 계산기 서브메뉴 (Mobile) - 한국어일 때 연봉 계산기 추가
    const salaryCalcItemMobile = CURRENT_LANG === 'kr' ? '<li><a href="' + LANG_PATH + 'salary-calculator.html" class="' + (activePageId === 'salaryCalculator' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.salaryCalculator + '</a></li>' : '';
    const calculatorMobile = '<li><details><summary class="font-medium ' + (isCalculatorActive ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.calculator + '</summary><ul class="p-2 bg-slate-50 rounded-lg">' + salaryCalcItemMobile + '<li><a href="' + LANG_PATH + 'compound-interest-calculator.html" class="' + (activePageId === 'compoundInterest' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.compoundInterest + '</a></li><li><a href="' + LANG_PATH + 'savings-goal-calculator.html" class="' + (activePageId === 'savingsGoal' ? 'text-blue-600' : 'text-slate-600') + '">' + lang.nav.savingsGoal + '</a></li></ul></details></li>';

    // 언어 스위치 (Mobile)
    const langSwitchMobile = '<li class="border-t border-slate-100 mt-2 pt-2"><a onclick="handleLanguageChange(\'' + otherLang + '\', \'' + activePageId + '\')" class="text-slate-600 cursor-pointer flex items-center gap-2"><svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>' + otherLangLabel + '</a></li>';

    header.innerHTML = '<div class="container mx-auto px-4"><div class="navbar min-h-16 p-0"><div class="navbar-start"><a href="' + LANG_PATH + 'index.html" class="flex items-center gap-3 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors"><img src="' + ROOT_PATH + 'images/logo.png" alt="Economy Helper" class="h-8 w-8 object-contain scale-150"><span class="hidden sm:inline">Economy Helper</span></a></div><div class="navbar-end"><ul class="menu menu-horizontal px-1 hidden md:flex gap-1 items-center">' + navItemsDesktop + calculatorDropdownDesktop + langSwitchDesktop + '</ul><div class="dropdown dropdown-end md:hidden"><label tabindex="0" class="btn btn-ghost btn-circle"><svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" /></svg></label><ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">' + navItemsMobile + calculatorMobile + langSwitchMobile + '</ul></div></div></div></div>';
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
    const prefix = CURRENT_LANG === 'kr' ? '투자 성향 테스트 결과, 나는' : 'My Investment Personality Test Result:';
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
        if (copyText) copyText.textContent = CURRENT_LANG === 'kr' ? '링크 복사' : 'Copy Link';
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
    const copiedText = CURRENT_LANG === 'kr' ? '복사됨!' : 'Copied!';
    const defaultText = CURRENT_LANG === 'kr' ? '링크 복사' : 'Copy Link';

    navigator.clipboard.writeText(fullText).then(() => {
        if (copyTextEl) {
            copyTextEl.textContent = copiedText;
            setTimeout(() => { copyTextEl.textContent = defaultText; }, 2000);
        }
    });
}
