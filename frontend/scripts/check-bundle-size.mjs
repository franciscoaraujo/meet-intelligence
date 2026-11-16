// Spec 008, CA-11: rode depois de `npm run build`.
// - Nenhum arquivo JS do build passa de 500 KB.
// - O chunk da página de login não importa o chunk da sala ao vivo.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const LIMIT_BYTES = 500 * 1000;
const assets = join(process.cwd(), 'dist', 'assets');

let files;
try {
  files = readdirSync(assets).filter((f) => f.endsWith('.js'));
} catch {
  console.error('dist/assets não encontrado: rode `npm run build` antes.');
  process.exit(1);
}

const failures = [];
const sizes = files.map((f) => ({ file: f, bytes: statSync(join(assets, f)).size })).sort((a, b) => b.bytes - a.bytes);
for (const { file, bytes } of sizes) {
  if (bytes > LIMIT_BYTES) failures.push(`${file} tem ${(bytes / 1000).toFixed(1)} KB (limite 500 KB)`);
}

const login = files.find((f) => f.startsWith('LoginPage-'));
if (!login) {
  failures.push('chunk LoginPage-*.js não encontrado (a rota de login deixou de ser carregada sob demanda?)');
} else if (readFileSync(join(assets, login), 'utf8').includes('LiveMeetingPage-')) {
  failures.push(`${login} importa o chunk da sala ao vivo`);
}

console.log('Maiores arquivos JS:');
for (const { file, bytes } of sizes.slice(0, 5)) console.log(`  ${(bytes / 1000).toFixed(1).padStart(7)} KB  ${file}`);

if (failures.length > 0) {
  console.error('\nFalhou:\n- ' + failures.join('\n- '));
  process.exit(1);
}
console.log('\nOK: nenhum chunk acima de 500 KB e o login não carrega a sala ao vivo.');
