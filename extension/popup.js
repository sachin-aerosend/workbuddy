chrome.storage.session.get('connected').then(({ connected }) => {
  document.getElementById('status').textContent = connected
    ? '✓ Connected to your cat.'
    : 'The cat app isn’t running. Blocked pages still close, just without the cat.';
});
