// Tells IndexNow search engines (Bing, Yandex, Seznam, Naver…) about every URL in the live sitemap.
// Run after deploying new or updated pages:  npm run indexnow
const HOST = 'www.workbuddycat.online';
const KEY = 'e942a0ba99dcfdf0745dd3bf44b17318'; // public by design: it's also served at /<key>.txt

(async () => {
  const xml = await (await fetch(`https://${HOST}/sitemap.xml`)).text();
  const urlList = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m => m[1]);
  const res = await fetch('https://api.indexnow.org/indexnow', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ host: HOST, key: KEY, keyLocation: `https://${HOST}/${KEY}.txt`, urlList }),
  });
  console.log(`IndexNow: submitted ${urlList.length} URLs -> HTTP ${res.status}${res.status === 202 || res.status === 200 ? ' (accepted)' : ''}`);
})();
