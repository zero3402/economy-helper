// Economy Helper - Shared Components

const NAV_ITEMS = [
    { id: 'dictionary', label: 'Dictionary', href: 'index.html' },
    { id: 'personality', label: 'Personality Test', href: 'personality-test.html' },
    { id: 'salary', label: 'Salary Calculator', href: 'salary-calculator.html' }
];

// Render Header Component
function renderHeader(activePageId) {
    const header = document.getElementById('header');
    if (!header) return;

    const navItemsDesktop = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive
            ? 'text-blue-600 bg-blue-50'
            : 'text-slate-600 hover:text-blue-600 hover:bg-blue-50';
        return `<li><a href="${item.href}" class="font-medium ${activeClass} rounded-lg">${item.label}</a></li>`;
    }).join('');

    const navItemsMobile = NAV_ITEMS.map(item => {
        const isActive = item.id === activePageId;
        const activeClass = isActive ? 'text-blue-600' : 'text-slate-600';
        return `<li><a href="${item.href}" class="font-medium ${activeClass}">${item.label}</a></li>`;
    }).join('');

    header.innerHTML = `
        <div class="container mx-auto px-4">
            <div class="navbar min-h-16 p-0">
                <div class="navbar-start">
                    <a href="index.html" class="flex items-center gap-2 text-xl font-bold text-slate-800 hover:text-blue-600 transition-colors">
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-blue-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" />
                        </svg>
                        <span class="hidden sm:inline">Economy Helper</span>
                    </a>
                </div>
                <div class="navbar-end">
                    <!-- Desktop Menu -->
                    <ul class="menu menu-horizontal px-1 hidden md:flex gap-1">
                        ${navItemsDesktop}
                    </ul>
                    <!-- Mobile Menu -->
                    <div class="dropdown dropdown-end md:hidden">
                        <label tabindex="0" class="btn btn-ghost btn-circle">
                            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
                            </svg>
                        </label>
                        <ul tabindex="0" class="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow-lg bg-white rounded-xl w-52 border border-slate-100">
                            ${navItemsMobile}
                        </ul>
                    </div>
                </div>
            </div>
        </div>
    `;
}

// Render Footer Component
function renderFooter() {
    const footer = document.getElementById('footer');
    if (!footer) return;

    footer.innerHTML = `
        <div class="container mx-auto px-4 py-6">
            <p class="text-center text-slate-500 text-sm">
                &copy; ${new Date().getFullYear()} Economy Helper. All rights reserved.
            </p>
        </div>
    `;
}

// Initialize Components
function initComponents(activePageId) {
    renderHeader(activePageId);
    renderFooter();
}
