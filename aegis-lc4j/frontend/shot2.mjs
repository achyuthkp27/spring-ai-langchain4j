import { chromium } from "playwright";

const browser = await chromium.launch({
  executablePath:
    "/Users/achyuthkp/Library/Caches/ms-playwright/chromium-1228/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing",
});
const page = await browser.newPage({ viewport: { width: 900, height: 900 } });
await page.goto("http://localhost:3001/", { waitUntil: "networkidle" });
await page.waitForTimeout(1200);

await page.getByRole("button", { name: "New chat", exact: true }).click();
await page.waitForTimeout(600);
await page.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/hero-fresh.png" });

// Mobile width too
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
await mobile.goto("http://localhost:3001/", { waitUntil: "networkidle" });
await mobile.waitForTimeout(1200);
await mobile.screenshot({ path: "/private/tmp/claude-501/-Users-achyuthkp-notionn/ccde6154-70eb-4b83-82f0-acd6aa060cee/scratchpad/mobile.png" });

await browser.close();
console.log("done");
