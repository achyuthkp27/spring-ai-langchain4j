import { chromium } from "playwright";

const port = process.argv[2] || "3001";
const browser = await chromium.launch({
  executablePath:
    "/Users/achyuthkp/Library/Caches/ms-playwright/chromium-1228/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
});
const page = await browser.newPage({ viewport: { width: 900, height: 900 } });

await page.goto(`http://localhost:${port}/`, { waitUntil: "networkidle" });
await page.waitForTimeout(1500); // splash + hydration

// Hero (empty-state) composer
await page.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/hero.png" });

const box = await page.locator("#composer-input").first().boundingBox();
if (box) {
  await page.screenshot({
    path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/hero-composer-crop.png",
    clip: { x: Math.max(0, box.x - 60), y: Math.max(0, box.y - 60), width: box.width + 120, height: box.height + 260 },
  });
}

// Focus state
await page.locator("#composer-input").first().click();
await page.waitForTimeout(300);
await page.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/hero-focused.png" });
await page.locator("#composer-input").first().type("What is the dispute filing deadline for a debit card charge?");
await page.waitForTimeout(300);
await page.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/hero-typed.png" });

// Send it, wait for the docked (bottom) composer state once a turn exists
await page.keyboard.press("Enter");
await page.waitForTimeout(1200);
await page.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/docked-streaming.png" });

await browser.close();
console.log("done");
