/* Port of recovered donation-link-2-ad original renderer. Source preserved under upstream-reference. */
function donationBanner(options) {
  if (options == null) options = {};

  // ============================================================
  //  CONFIG - edit the values below to point at your accounts
  // ============================================================
  const defaults = {
    name: "I am canceled",                        // shown in the banner text
    tagline: "Liked it? A coffee-sized tip keeps it going!",
    dark: "auto",                             // "auto" (follows device) | "light" | "dark"
    compact: false,                           // tighter single-line layout
    showExplorer: true,                       // show the small "view on Solscan" link
    chips: { paypal: true, cashApp: true, solana: true, eth: true },
    paypal: {
      label: "PayPal",
      url: "https://www.paypal.com/qrcodes/p2pqrc/WGMNG3RKN97BL"
    },
    cashApp: {
      label: "Cash App",
      tag: "$falcox904",
      url: "https://cash.app/$falcox904"
    },
    solana: {
      label: "SOL",
      address: "4guatesjezaC5h2niYR6ZDmZ6pQagQAAvtnwBf7NMNpK",
      explorerUrl: "https://solscan.io/account/4guatesjezaC5h2niYR6ZDmZ6pQagQAAvtnwBf7NMNpK"
    },
    eth: {
      label: "ETH",
      address: "0xFBdB59298ea0B9D867897CCEDDb0De1e2B03909c",
      explorerUrl: "https://etherscan.io/address/0xFBdB59298ea0B9D867897CCEDDb0De1e2B03909c"
    }
  };
  // ============================================================

  const o = Object.assign({}, defaults, options);
  o.paypal = Object.assign({}, defaults.paypal, (options.paypal || {}));
  o.cashApp = Object.assign({}, defaults.cashApp, (options.cashApp || {}));
  o.solana = Object.assign({}, defaults.solana, (options.solana || {}));
  o.eth = Object.assign({}, defaults.eth, (options.eth || {}));
  const show = Object.assign({ paypal: true, cashApp: true, solana: true, eth: true }, (options.chips || {}));

  const esc = s => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  const name = esc(o.name);
  const tagline = esc(o.tagline);
  const ppUrl = esc(o.paypal.url), ppLbl = esc(o.paypal.label);
  const caUrl = esc(o.cashApp.url), caLbl = esc(o.cashApp.label), caTag = esc(o.cashApp.tag);
  const solAddr = esc(o.solana.address);
  const solLbl = esc(o.solana.label);
  const solExpl = esc(o.solana.explorerUrl || ("https://solscan.io/account/" + solAddr));
  const copyLabel = esc(o.solana.copyLabel || ("Copy " + solLbl + " address"));
  const ethAddr = esc(o.eth.address);
  const ethLbl = esc(o.eth.label);
  const ethExpl = esc(o.eth.explorerUrl || ("https://etherscan.io/address/" + ethAddr));
  const ethCopyLabel = esc(o.eth.copyLabel || ("Copy " + ethLbl + " address"));

  const glyphPaypal = '<svg class="dl2a-g" viewBox="0 0 384 512" aria-hidden="true"><path fill="#003087" d="M111.4 295.9c-3.5 19.2-17.4 108.7-21.5 134-.3 1.8-1 2.5-3 2.5H12.3c-7.6 0-13.1-6.6-12.9-13.4 1-6.6 17.4-108.5 21.5-133.8 3.5-19.2 17.4-108.7 21.5-134.3.4-1.8 1-2.6 2.9-2.6h55.8c74.9 0 87.1 44.2 87.1 86.1 0 45.2-29.5 73.1-77.5 73.5zM206.6 299.5c3.1-18.9 15-105.1 18.6-124.6 3.6-19.6 15-124.9 18.6-137.2 2.5-9.4 9.4-12.3 18.3-12.3h61.8c-5.5 22.5-12.1 49.3-18.3 73.8-6.3 25.1-12.2 48.9-18.4 73.8-13.1 53.2-24.3 98.9-37.7 152.9-4.4 17.7-10.3 22.3-27.9 22.3-26.5 0-52.9-.3-79.3-.3-1.7 0-3.3-.1-4.7-.4 8.2-27.4 21.4-112.4 37.3-165.5z"/></svg>';

  const glyphCashapp = '<span class="dl2a-gca" aria-hidden="true">$</span>';

  const glyphSolana = '<svg class="dl2a-g" viewBox="0 0 24 20" aria-hidden="true"><defs><linearGradient id="dl2a-sgrad" x1="0" y1="0" x2="24" y2="20" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#9945FF"/><stop offset="1" stop-color="#14F195"/></linearGradient></defs><g transform="translate(11 10) skewX(-30)"><rect x="-9" y="-7.5" width="18" height="4.2" rx="2.1" fill="url(#dl2a-sgrad)"/><rect x="-8" y="-2" width="16" height="4.2" rx="2.1" fill="url(#dl2a-sgrad)"/><rect x="-9" y="3.5" width="18" height="4.2" rx="2.1" fill="url(#dl2a-sgrad)"/></g></svg>';

  const glyphEth = '<svg class="dl2a-g" viewBox="0 0 256 417" aria-hidden="true"><path fill="#8a92f2" d="M127.961 0l-2.795 9.5v275.668l2.795 2.79 127.962-75.638z"/><path fill="#454a75" d="M127.962 0L0 212.32l127.962 75.639V154.158z"/><path fill="#8a92f2" d="M127.961 312.187l-1.575 1.92v98.199l1.575 4.6L256 236.587z"/><path fill="#454a75" d="M127.962 416.905v-104.72L0 236.585z"/><path fill="#b7befc" d="M127.961 287.958l127.96-75.637-127.96-58.162z"/><path fill="#6d74b2" d="M0 212.32l127.96 75.638v-133.8z"/></svg>';

  const heartSvg = '<svg class="dl2a-heart" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>';

  const cssRules = [
    '.dl2a\{--dl2a-bg:#ffffff;--dl2a-line:rgba(2,6,23,.1);--dl2a-txt:#0f172a;--dl2a-sub:#64748b;--dl2a-acc:#e0245e;--dl2a-chipline:rgba(2,6,23,.08);--dl2a-chipbg:#f1f5f9;--dl2a-chipbg2:#ffffff;--dl2a-chipshadow:0 1px 2px rgba(2,6,23,.06);color:var(--dl2a-txt);font-family:ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;\}',
    '.dl2a-bar\{box-sizing:border-box;width:100%;display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:10px 14px;padding:12px 14px 12px 16px;background:var(--dl2a-bg);border:1px solid var(--dl2a-line);border-radius:16px;box-shadow:0 1px 2px rgba(2,6,23,.04),0 8px 24px -12px rgba(2,6,23,.12);margin:0;font-size:14px;line-height:1.35;text-align:left;transition:box-shadow .15s ease;\}',
    '.dl2a-bar:hover\{box-shadow:0 1px 2px rgba(2,6,23,.04),0 12px 28px -10px rgba(2,6,23,.18);\}',
    '.dl2a-msg\{display:flex;align-items:center;gap:10px;min-width:0;\}',
    '.dl2a-heart\{width:17px;height:17px;flex:none;color:var(--dl2a-acc);\}',
    '.dl2a-txt\{display:flex;flex-direction:column;gap:1px;min-width:0;\}',
    '.dl2a-title\{font-weight:700;font-size:14.5px;letter-spacing:.1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\}',
    '.dl2a-sub\{color:var(--dl2a-sub);font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\}',
    '.dl2a-actions\{display:flex;align-items:center;gap:8px;flex-wrap:wrap;\}',
    '.dl2a-chip\{box-sizing:border-box;display:inline-flex;align-items:center;gap:8px;padding:8px 15px;border-radius:999px;border:1px solid var(--dl2a-chipline);font-weight:600;font-size:13.5px;line-height:1.2;text-decoration:none;cursor:pointer;white-space:nowrap;font-family:inherit;box-shadow:var(--dl2a-chipshadow);transition:transform .12s ease,box-shadow .12s ease,filter .12s ease;\}',
    '.dl2a-chip:hover\{transform:translateY(-1px);box-shadow:0 3px 10px -2px rgba(2,6,23,.25);filter:saturate(1.08);\}',
    '.dl2a-chip:active\{transform:translateY(0);\}',
    '.dl2a-g\{width:14px;height:14px;flex:none;\}',
    '.dl2a-gca\{flex:none;width:15px;height:15px;border-radius:4px;background:#00d632;color:#000;font-weight:800;font-size:12px;line-height:15px;text-align:center;font-family:Arial,Helvetica,sans-serif;\}',
    '.dl2a-pp\{background:#ffc439;color:#003087;border-color:rgba(0,48,135,.18);\}',
    '.dl2a-ca\{background:#00d632;color:#000;border-color:rgba(0,0,0,.12);\}',
    '.dl2a-ca .dl2a-gca\{background:#000;color:#00d632;\}',
    '.dl2a-grp\{display:inline-flex;align-items:center;gap:4px;\}',
    '.dl2a-et\{background:#e8eeff;color:#3439d0;border-color:rgba(98,126,234,.4);\}',
    '.dl2a-et:hover\{background:#dce5ff;\}',
    '.dl2a-sb\{background:var(--dl2a-chipbg);color:var(--dl2a-txt);border-color:var(--dl2a-chipline);\}',
    '.dl2a-sb:hover\{background:var(--dl2a-chipbg2);\}',
    '.dl2a-chip.dl2a-on\{background:#e8ffe9;border-color:rgba(16,185,129,.45);color:#047857;\}',
    '.dl2a-ext\{flex:none;display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;border-radius:50%;color:var(--dl2a-sub);text-decoration:none;font-size:14px;line-height:1;border:1px solid transparent;\}',
    '.dl2a-ext:hover\{color:var(--dl2a-txt);background:var(--dl2a-chipbg);\}',
    '.dl2a-compact .dl2a-bar\{padding:8px 12px 8px 14px;gap:8px 12px;\}',
    '.dl2a-compact .dl2a-chip\{padding:6px 12px;font-size:13px;\}',
    '.dl2a-compact .dl2a-sub\{display:none;\}',
    '@media(max-width:560px)\{.dl2a-bar\{flex-direction:column;align-items:flex-start;\}.dl2a-actions\{width:100%;\}.dl2a-chip\{flex:1;justify-content:center;\}\}',
    '.dl2a-theme-light\{--dl2a-bg:#ffffff;--dl2a-line:rgba(2,6,23,.1);--dl2a-txt:#0f172a;--dl2a-sub:#64748b;--dl2a-acc:#e0245e;--dl2a-chipline:rgba(2,6,23,.08);--dl2a-chipbg:#f1f5f9;--dl2a-chipbg2:#ffffff;--dl2a-chipshadow:0 1px 2px rgba(2,6,23,.06);\}',
    '.dl2a-theme-dark\{--dl2a-bg:#11131c;--dl2a-line:rgba(255,255,255,.1);--dl2a-txt:#eef0f6;--dl2a-sub:#98a1b5;--dl2a-acc:#fb7185;--dl2a-chipline:rgba(255,255,255,.12);--dl2a-chipbg:#181b26;--dl2a-chipbg2:#1d2130;--dl2a-chipshadow:0 1px 2px rgba(0,0,0,.4);\}',
    '@media(prefers-color-scheme:dark)\{.dl2a.dl2a-theme-auto\{--dl2a-bg:#11131c;--dl2a-line:rgba(255,255,255,.1);--dl2a-txt:#eef0f6;--dl2a-sub:#98a1b5;--dl2a-acc:#fb7185;--dl2a-chipline:rgba(255,255,255,.12);--dl2a-chipbg:#181b26;--dl2a-chipbg2:#1d2130;--dl2a-chipshadow:0 1px 2px rgba(0,0,0,.4);\}\}',
    '@media(prefers-color-scheme:dark)\{.dl2a.dl2a-theme-auto .dl2a-chip.dl2a-on\{background:#06301f;border-color:rgba(52,211,153,.4);color:#6ee7b7;\}\}',
    '.dl2a-theme-dark .dl2a-chip.dl2a-on\{background:#06301f;border-color:rgba(52,211,153,.4);color:#6ee7b7;\}'
  ];

  const styleBlock = '<style>' + cssRules.join('') + '</style>';

  const actions = [];
  if (show.paypal) {
    actions.push('<a class="dl2a-chip dl2a-pp" href="' + ppUrl + '" target="_blank" rel="noopener noreferrer" title="Donate via ' + ppLbl + '">' + glyphPaypal + '<span>' + ppLbl + '</span></a>');
  }
  if (show.cashApp) {
    actions.push('<a class="dl2a-chip dl2a-ca" href="' + caUrl + '" target="_blank" rel="noopener noreferrer" title="Donate via ' + caLbl + ' (' + caTag + ')">' + glyphCashapp + '<span>' + caLbl + '</span></a>');
  }
  if (show.solana) {
    const onclick = 'event.preventDefault();event.stopPropagation();var a=\'' + solAddr + '\',l=this.querySelector(\'.dl2a-lbl\');document.hasFocus()&&navigator.clipboard&&navigator.clipboard.writeText?navigator.clipboard.writeText(a):window.prompt(\'Copy SOL wallet address:\',a);l.textContent=\'Copied!\';this.classList.add(\'dl2a-on\');';
    actions.push('<span class="dl2a-grp">' +
      '<button type="button" class="dl2a-chip dl2a-sb" title="Copy SOL wallet address to clipboard" onclick="' + onclick + '">' + glyphSolana + '<span class="dl2a-lbl">' + copyLabel + '</span></button>' +
      (o.showExplorer ? '<a class="dl2a-ext" href="' + solExpl + '" target="_blank" rel="noopener noreferrer" title="View wallet on Solscan">&#8599;</a>' : '') +
      '</span>');
  }

  if (show.eth) {
    const ethOnclick = 'event.preventDefault();event.stopPropagation();var a=\'' + ethAddr + '\',l=this.querySelector(\'.dl2a-lbl\');document.hasFocus()&&navigator.clipboard&&navigator.clipboard.writeText?navigator.clipboard.writeText(a):window.prompt(\'Copy ETH wallet address:\',a);l.textContent=\'Copied!\';this.classList.add(\'dl2a-on\');';
    actions.push('<span class="dl2a-grp">' +
      '<button type="button" class="dl2a-chip dl2a-et" title="Copy ETH wallet address to clipboard" onclick="' + ethOnclick + '">' + glyphEth + '<span class="dl2a-lbl">' + ethCopyLabel + '</span></button>' +
      (o.showExplorer ? '<a class="dl2a-ext" href="' + ethExpl + '" target="_blank" rel="noopener noreferrer" title="View wallet on Etherscan">&#8599;</a>' : '') +
      '</span>');
  }


  const compactCls = o.compact ? ' dl2a-compact' : '';
  const themeCls = (o.dark === 'light' || o.dark === 'dark') ? o.dark : 'auto';

  return '<div class="dl2a' + compactCls + ' dl2a-theme-' + themeCls + '">' + styleBlock +
    '<div class="dl2a-bar">' +
      '<div class="dl2a-msg">' + heartSvg +
        '<div class="dl2a-txt"><span class="dl2a-title">Support <span class="dl2a-name">' + name + '</span></span>' +
        (o.compact ? '' : '<span class="dl2a-sub">' + tagline + '</span>') +
        '</div>' +
      '</div>' +
      '<div class="dl2a-actions">' + actions.join('') + '</div>' +
    '</div>' +
    '</div>';

}
