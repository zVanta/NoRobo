/** SMS text feature extraction — tokenizer with structural pseudo-tokens. */
const PSEUDO = {
  URL: '__url__',
  MONEY: '__money__',
  LONGNUM: '__longnum__',
  EXCLAIM: '__exclaim__'
};

function tokenize(text) {
  const t = String(text || '').toLowerCase();
  return t
    .replace(/https?:\/\/\S+|www\.\S+/g, ` ${PSEUDO.URL} `)
    .replace(
      /\$\s?\d[\d,.]*|£\s?\d[\d,.]*|€\s?\d[\d,.]*|\d+\s?(?:dollars?|usd|pounds?|gbp|eur)/g,
      ` ${PSEUDO.MONEY} `
    )
    .replace(/\b\d{4,}\b/g, ` ${PSEUDO.LONGNUM} `)
    .replace(/!{2,}/g, ` ${PSEUDO.EXCLAIM} `)
    .replace(/[^a-z0-9_\s]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);
}

module.exports = { tokenize, PSEUDO };
