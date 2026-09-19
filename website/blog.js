// Blog pages: play the pixel-cat sprites.
(() => {
  const ANIMS = {
    idle: [10, 8], blink: [5, 8], yawn: [5, 5], happy: [4, 6], surprised: [2, 6], groom: [6, 5],
    swipe: [6, 8], typing: [4, 6], dangle: [4, 6], walk: [4, 7], run: [4, 12], pounce: [7, 8],
    stretch: [4, 3], loaf: [4, 2], sleep: [4, 2], roll: [4, 8], box: [4, 4], dracula: [6, 6],
  };
  document.querySelectorAll('.sprite[data-anim]').forEach(el => {
    const [frames, fps] = ANIMS[el.dataset.anim] || ANIMS.idle;
    el.style.backgroundImage = `url(/assets/sprites/${el.dataset.anim}.png)`;
    el.style.setProperty('--frames', frames);
    el.style.setProperty('--dur', `${frames / fps}s`);
  });
})();
