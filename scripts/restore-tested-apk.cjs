const fs = require('node:fs');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Input and output APK paths are required');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const source = fs.readFileSync(input);
const recipe = JSON.parse(zlib.gunzipSync(Buffer.from(fs.readFileSync('release-assets/3.3.5-signed-apk.patch.gz.base64','utf8'), 'base64')));
if (recipe.version !== 1 || source.length !== recipe.sourceLength || sha(source) !== recipe.sourceSha256) throw new Error('Unsigned build does not match the tested source artifact');
const data = Buffer.concat(recipe.parts.map(part => {
    if (!part.copy) return Buffer.from(part.data, 'base64');
    const [offset, length] = part.copy;
    if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 0 || offset + length > source.length) throw new Error('Invalid patch range');
    return source.subarray(offset, offset + length);
}));
if (data.length !== recipe.signedLength || sha(data) !== recipe.signedSha256) throw new Error('Restored APK does not match the user-tested signed package');
fs.writeFileSync(output, data);
console.log('Restored exact tested signed APK: ' + recipe.signedSha256);
