const fs = require('node:fs');
const crypto = require('node:crypto');
const [input, recipient, output] = process.argv.slice(2);
if (!input || !recipient || !output) throw new Error('Input, public recipient key and output are required');
const key = crypto.randomBytes(32);
const nonce = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce);
const ciphertext = Buffer.concat([cipher.update(fs.readFileSync(input)), cipher.final()]);
const wrappedKey = crypto.publicEncrypt({
    key: fs.readFileSync(recipient),
    padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: 'sha256'
}, key);
fs.writeFileSync(output, JSON.stringify({
    version: 1,
    algorithm: 'RSA-OAEP-SHA256+AES-256-GCM',
    wrappedKey: wrappedKey.toString('base64'),
    nonce: nonce.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
    ciphertext: ciphertext.toString('base64')
}));
console.log('Encrypted signing backup prepared.');
