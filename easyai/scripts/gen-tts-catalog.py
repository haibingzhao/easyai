#!/usr/bin/env python3
"""Regenerate the TTS catalog artifacts from tts-voices.json.

Source of truth: ./tts-voices.json — normalised from the vendor voice list
(https://help.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list), whose HTML carries all
five tabs inline. To add or fix a voice, edit the JSON and re-run this script; do not hand-edit
the generated files, whose single JSON literal per model is 35 KB wide and impossible to review.

    python3 gen-tts-catalog.py            # writes all three artifacts
    python3 gen-tts-catalog.py --check    # verify the artifacts match the JSON (CI gate)

Artifacts (all generated, never edited by hand):

    seed-tts-catalog.sql            manual hot-patch for a running database (psql / H2 shell)
    V47__expand_qwen_audio_speech_tags.sql Flyway migration — the normal upgrade path
    (V45 is frozen; this one rewrites the same rows)
    tts-voices-appendix.md          the human-readable voice tables for the plan document

Voice object fields (see the header of the generated SQL for the full contract):
    voice, name, group, gender, age?, traits[], scenarios[], accent?, languages[],
    languageLabel?, languageInferred?, dialects[], ssml, instruction, timestamp
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = json.load(open(os.path.join(HERE, 'tts-voices.json'), encoding='utf-8'))
SQL_OUT = os.path.join(HERE, 'seed-tts-catalog.sql')
MD_OUT = os.path.join(HERE, 'tts-voices-appendix.md')
PROTOCOL_KT = os.path.join(
    HERE, '..', 'easyai-speech', 'src', 'main', 'kotlin',
    'com', 'easy', 'easyai', 'speech', 'DashScopeTtsProtocol.kt')
# V45 already shipped and is frozen: Flyway rejects a changed checksum, so the tag vocabulary
# expansion goes out as a new version that rewrites the same four rows.
MIGRATION_OUT = os.path.join(
    HERE, '..', 'easyai-autoconfigure', 'easyai-r2dbc-autoconfigure',
    'src', 'main', 'resources', 'db', 'migration',
    'V47__expand_qwen_audio_speech_tags.sql')

META = {
    'qwen-audio-3.1-tts-plus': dict(
        display='Qwen-Audio-3.1 TTS Plus',
        desc='Qwen-Audio-TTS 3.1 旗舰音色，高保真，支持指令控制、情感标签与 SSML',
        default='longanlingxin_v3.1'),
    'qwen-audio-3.1-tts-flash': dict(
        display='Qwen-Audio-3.1 TTS Flash',
        desc='Qwen-Audio-TTS 3.1 低延迟，139 个系统音色，含 8 种方言、8 种常用语言与 51 种小语种',
        default='longanhuan_v3.1'),
    'qwen-audio-3.0-tts-plus': dict(
        display='Qwen-Audio-3.0 TTS Plus',
        desc='Qwen-Audio-TTS 3.0 旗舰，社交陪伴场景',
        default='longanlingxin'),
    'qwen-audio-3.0-tts-flash': dict(
        display='Qwen-Audio-3.0 TTS Flash',
        desc='Qwen-Audio-TTS 3.0 低延迟，含儿童与角色音',
        default='longanfengyue'),
}

# Sample rates are six discrete values — the protocol has no continuous range — and 12000 is not
# one of them. Kept in sync with DashScopeTtsProtocol.SAMPLE_RATES.
SAMPLE_RATES = [8000, 16000, 22050, 24000, 44100, 48000]

BASE = {
    'supportsInstruction': True,
    'supportsEmotionTags': True,
    'supportsSsml': True,
    'supportsLatex': False,
    'supportsWordTimestamp': True,
    'supportsHotFix': True,
    'formats': ['mp3', 'pcm', 'wav', 'opus'],
    'sampleRates': SAMPLE_RATES,
    # Protocol ceilings. The Chat read-aloud product cap (20 000 characters) is a policy of one
    # endpoint, not a model capability, so it stays out of the catalog.
    'maxCharsPerFrame': 20000,
    'maxCharsPerTask': 200000,
    'docsUrl': 'https://docs.bailian.console.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list',
}

# Emotion and paralinguistic tags, as published in 《情感与副语言标签》
# (non-realtime-tts-user-guide). They are plain text written straight into `input.text` — no
# request parameter switches them on, and they are unrelated to `enable_ssml`.
#
# Control tags change the delivery from where they appear until the next control tag or until the
# engine splits the sentence, so they are also usable as a whole-segment style.
CONTROL_TAGS = [
    '[sad]', '[amazed]', '[deep and loud shouting]', '[trembling]', '[angry]', '[excited]',
    '[sarcastic]', '[curious]', '[like dracula]', '[bored]', '[tired]', '[scornful]', '[shouting]',
    '[asmr]', '[panicked]', '[mischievously]', '[empathetic]', '[whispers]', '[reluctantly]',
    '[crying]', '[serious]', '[very slowly]', '[very fast]',
]

# Paralinguistic tags insert one sound at their position and leave the surrounding style alone.
# Kept in a separate field because they must not be used as a segment-level prefix: `[laughing]`
# at the head of a segment means "laugh, then say everything", not "laugh here".
PARALINGUISTIC_TAGS = [
    '[gasp]', '[sighing]', '[clears throat]', '[giggles]', '[laughing]', '[cough]', '[snorts]',
]

GROUPS = {'flagship', 'multilingual', 'premium-zh', 'premium-en', 'other', 'minor', 'legacy'}

# Fixed so regeneration is byte-stable (`--check` and Flyway checksums depend on it). These rows are
# documentation-derived, so the exact seed timestamp carries no meaning.
SEED_EPOCH_MS = 1793203200000

HEADER_BODY = '''-- GENERATED FILE — do not edit by hand. Change tts-voices.json and run
--   python3 gen-tts-catalog.py
--
-- Scope: qwen-audio-3.1-tts-plus / qwen-audio-3.1-tts-flash /
--        qwen-audio-3.0-tts-plus / qwen-audio-3.0-tts-flash
--        (157 system voices in total: 4 / 139 / 2 / 12)
--
-- Idempotent and re-runnable: the column is added if missing, every row is UPDATEd first and
-- INSERTed only when absent, because PostgreSQL and the H2 file database share no upsert syntax
-- (see V33__backfill_default_agent_watch_tool.sql for the WHERE NOT EXISTS precedent).
-- Running this file twice is safe; running it after a data fix refreshes the voice lists.
--
-- These rows are maintained from the vendor docs, so `source` is 'bailian-tts-doc'
-- (not 'bailian-refresh'). A catalog Refresh never produces speech_capabilities, and the
-- store preserves the column when the incoming entry carries none — so re-running Refresh
-- cannot wipe the voice lists below.
--
-- Data provenance (2026-10-29, tags expanded 2026-10-31):
--   models/voices/traits/scenarios/languages : qwen-audio-tts-voice-list
--   instruction / emotion tags               : realtime-tts-user-guide, 情感与副语言标签
--   SSML support                             : ssml-latex-user-guide (all four models listed)
--   LaTeX                                    : contradictory across docs -> false, flip after the probe in the plan
--
-- Tag fields:
--   emotionTags[]        控制类标签，改变其后文本的语气，直到下一个控制类标签或句子被切开
--   paralinguisticTags[] 副语言标签，只在原位插一声，不改变前后语气；不可用作段首前缀
--
-- Voice object shape (consumed by the console form and, later, by the model itself):
--   voice           API 参数值，必须原样传给 input.voice
--   name            展示名
--   group           flagship | multilingual | premium-zh | premium-en | other | minor | legacy
--   gender          female | male | child | (缺省 = 文档未标注)
--   age             仅 3.0 系列文档给出
--   traits[]        声线特质，如 ["元气","亲切","自然"]
--   scenarios[]     官方适用场景，如 ["广告营销","旁白"]
--   accent          英文音色口音，如 "英式女声"
--   languages[]     BCP-47，zh/en/ja...；小语种落到具体语种
--   languageLabel   小语种中文名（保加利亚语）
--   languageInferred 文档未标注语种、按普通话推断的标记（3.1 其他系统音色 / 3.1-plus 旗舰）
--   dialects[]      该音色可通过 instruction 切换的方言
--   ssml/instruction/timestamp  该音色是否支持三项特性（不是模型级结论）
'''

SQL_HEADER = '''-- =============================================
-- Seed Qwen-Audio-TTS speech capabilities into the model catalog.
--
-- Hot-patch copy: run this against a live database when a redeploy is not possible.
-- The same statements ship as V47__expand_qwen_audio_speech_tags.sql, which is the normal path.
--
''' + HEADER_BODY + '''--
-- Usage:
--   psql "$DATABASE_URL" -f seed-tts-catalog.sql
--   H2 (desktop server) has no psql; restart to run the migration, or paste this into
--   `java -cp h2.jar org.h2.tools.Shell`.
-- =============================================

-- The hot-patch script must bring its own DDL: a database that never ran V45 has no such column.
-- V47 carries the same ALTER, so a database that starts at V47 gets it too.
ALTER TABLE model_catalog ADD COLUMN IF NOT EXISTS speech_capabilities TEXT;
'''

MIGRATION_HEADER = '''-- =============================================
-- Seed Qwen-Audio-TTS speech capabilities into the model catalog.
--
-- Flyway copy — the normal upgrade path: any environment picks these rows up on start.
--
''' + HEADER_BODY + '''--
-- IMMUTABLE ONCE APPLIED. Flyway rejects a changed checksum, so a voice fix after this version has
-- run anywhere must ship as a new migration (V48, V49, …) plus a regenerated hot-patch script.
-- =============================================

ALTER TABLE model_catalog ADD COLUMN IF NOT EXISTS speech_capabilities TEXT;
'''

SQL_FOOTER = '''
-- ---------- verification ----------
-- Expect 4 rows and voice counts 4 / 139 / 2 / 12 (157 total).
--
-- SELECT model_id, source FROM model_catalog
--  WHERE model_id LIKE 'qwen-audio-%' ORDER BY model_id;
--
-- PostgreSQL: inspect the JSON
-- SELECT model_id,
--        speech_capabilities::jsonb ->> 'defaultVoice' AS default_voice,
--        jsonb_array_length(speech_capabilities::jsonb -> 'voices') AS voices
--   FROM model_catalog WHERE model_id LIKE 'qwen-audio-%' ORDER BY model_id;
--
-- ---------- append a voice later (PostgreSQL only, H2 lacks jsonb) ----------
-- Prefer editing tts-voices.json + re-running the generator; this is the manual escape hatch.
-- UPDATE model_catalog
--    SET speech_capabilities = jsonb_set(
--          speech_capabilities::jsonb, '{voices}',
--          (speech_capabilities::jsonb -> 'voices')
--            || '[{"voice":"longxxx","name":"龙某某","group":"premium-zh","gender":"female","traits":[],"scenarios":[],"languages":["zh"],"dialects":[],"ssml":true,"instruction":true,"timestamp":true}]'::jsonb
--        )::jsonb::text,
--        updated_at = CAST(EXTRACT(EPOCH FROM NOW()) * 1000 AS BIGINT)
--  WHERE model_id = 'qwen-audio-3.1-tts-flash';
'''

VOICE_TAG = {'zh': '中', 'en': '英', 'ja': '日', 'ko': '韩', 'fr': '法', 'de': '德',
             'pt': '葡', 'it': '意', 'vi': '越', 'id': '印尼'}
GENDER = {'female': '女', 'male': '男', 'child': '儿童'}


def protocol_sample_rates():
    """Read the Kotlin protocol's own set, so the two lists cannot drift apart unnoticed."""
    try:
        source = open(PROTOCOL_KT, encoding='utf-8').read()
    except OSError:
        return None
    match = re.search(r'val SAMPLE_RATES\s*=\s*setOf\(([^)]*)\)', source)
    if not match:
        return None
    return {int(part) for part in re.findall(r'\d+', match.group(1))}


def validate():
    """Fail loudly on data the console would render wrong, instead of shipping it."""
    problems = []
    for model_id, model in DATA.items():
        if model_id not in META:
            problems.append(f'{model_id}: no display metadata in META')
            continue
        voices = model.get('voices', [])
        if not voices:
            problems.append(f'{model_id}: no voices')
            continue
        ids = [v['voice'] for v in voices]
        if len(ids) != len(set(ids)):
            dupes = sorted({x for x in ids if ids.count(x) > 1})
            problems.append(f'{model_id}: duplicate voice ids {dupes}')
        default = META[model_id]['default']
        if default not in ids:
            problems.append(f'{model_id}: defaultVoice {default!r} is not in voices[]')
        grouped = {g['group']: g for g in model.get('groups', [])}
        if set(grouped) != {v['group'] for v in voices}:
            problems.append(f'{model_id}: groups[] and voices[].group disagree')
        for key, group in grouped.items():
            actual = sum(1 for v in voices if v['group'] == key)
            if group.get('count', actual) != actual and len(group.get('voices', [])) != actual:
                problems.append(f'{model_id}/{key}: group carries {len(group.get("voices", []))} voices, '
                                f'but {actual} voices claim that group')
        for key in grouped:
            if key not in GROUPS:
                problems.append(f'{model_id}: unknown group {key!r}')
        for v in voices:
            if not v.get('voice') or not v.get('name'):
                problems.append(f'{model_id}: a voice is missing voice/name')
            if v.get('group') not in GROUPS:
                problems.append(f"{model_id}/{v.get('voice')}: unknown group {v.get('group')!r}")
            for flag in ('ssml', 'instruction', 'timestamp'):
                if not isinstance(v.get(flag), bool):
                    problems.append(f"{model_id}/{v['voice']}: {flag} must be a bool")
    # The previous assertion compared SAMPLE_RATES with itself and could never fail. The list has a
    # real counterpart in Kotlin, so drift is what this checks.
    protocol_rates = protocol_sample_rates()
    if protocol_rates is not None and protocol_rates != set(SAMPLE_RATES):
        problems.append(
            f'SAMPLE_RATES {sorted(SAMPLE_RATES)} drifted from DashScopeTtsProtocol {sorted(protocol_rates)}')
    if problems:
        raise SystemExit('tts-voices.json is inconsistent:\n  ' + '\n  '.join(problems))


def caps(model_id, model):
    payload = dict(BASE)
    payload.update({
        'voiceGroups': [{'key': g['group'], 'label': g['label'], 'count': len(g['voices'])}
                        for g in model['groups']],
        'defaultVoice': META[model_id]['default'],
        'emotionTags': CONTROL_TAGS,
        'paralinguisticTags': PARALINGUISTIC_TAGS,
        'voices': model['voices'],
    })
    return payload


def build_statements():
    """The UPDATE/INSERT block shared by the migration and the hot-patch script."""
    parts = []
    for mid, model in DATA.items():
        meta = META[mid]
        blob = json.dumps(caps(mid, model), ensure_ascii=False, separators=(',', ':')).replace("'", "''")
        n = len(model['voices'])
        counts = ' / '.join(str(len(g['voices'])) for g in model['groups'])
        label = ' + '.join(f"{g['label']} {len(g['voices'])}" for g in model['groups'])
        parts.append(f'''
-- ---------- {mid} ({n} voices: {counts}) ----------
-- {label}

UPDATE model_catalog
   SET speech_capabilities = '{blob}',
       updated_at = {SEED_EPOCH_MS}
 WHERE model_id = '{mid}';

INSERT INTO model_catalog
    (model_id, vendor, display_name, description, capabilities, default_options,
     reasoning_profile, pricing, speech_capabilities, source, raw_payload, created_at, updated_at)
SELECT '{mid}', 'bailian', '{meta['display']}',
       '{meta['desc']}',
       NULL, NULL, NULL, NULL,
       '{blob}',
       'bailian-tts-doc', NULL, {SEED_EPOCH_MS}, {SEED_EPOCH_MS}
WHERE NOT EXISTS (SELECT 1 FROM model_catalog WHERE model_id = '{mid}');
''')
    return ''.join(parts)


def build_sql():
    return SQL_HEADER + build_statements() + SQL_FOOTER


def build_migration():
    return MIGRATION_HEADER + build_statements()


def fmt_desc(v):
    bits = []
    if v.get('traits'):
        bits.append('、'.join(v['traits']))
    if v.get('accent'):
        bits.append(v['accent'])
    if v.get('age'):
        bits.append(f"{v['age']} 岁")
    return '／'.join(bits) if bits else '—'


def fmt_lang(v):
    langs = [VOICE_TAG.get(x, x) for x in v.get('languages', [])]
    if v.get('languageLabel'):
        langs = [v['languageLabel']]
    txt = '·'.join(langs) if langs else '—'
    if v.get('languageInferred'):
        txt += '（推断）'
    if v.get('dialects'):
        txt += '<br>方言：' + '、'.join(v['dialects'])
    return txt


def fmt_feat(v):
    if v.get('ssml') and v.get('instruction') and v.get('timestamp'):
        return '全支持'
    return ' '.join(f"{k}{'✓' if v.get(k) else '✗'}" for k in ('ssml', 'instruction', 'timestamp'))


def build_md():
    out = []
    for i, (mid, model) in enumerate(DATA.items(), 1):
        out.append(f"\n### D.{i} `{mid}`（{len(model['voices'])} 个）\n")
        for g in model['groups']:
            vs = g['voices']
            out.append(f"\n**{g['label']}（{len(vs)}）**\n")
            if g['group'] == 'minor':
                out.append('| 音色 | 本地名 | voice 参数 | 性别 | 语种 | 特性 |')
                out.append('|---|---|---|---|---|---|')
                for v in vs:
                    out.append(f"| {v['name']} | {v.get('localName', '')} | `{v['voice']}` | "
                               f"{GENDER.get(v.get('gender'), '—')} | "
                               f"{v.get('languageLabel', '')} | {fmt_feat(v)} |")
            elif g['group'] == 'premium-en':
                out.append('| 音色 | voice 参数 | 性别 | 口音 | 特性 |')
                out.append('|---|---|---|---|---|')
                for v in vs:
                    out.append(f"| {v['name']} | `{v['voice']}` | "
                               f"{GENDER.get(v.get('gender'), '—')} | "
                               f"{v.get('accent', '—')} | {fmt_feat(v)} |")
            elif g['group'] == 'multilingual':
                out.append('| 音色 | voice 参数 | 性别 | 方言（instruction 切换） | 语种 | 特性 |')
                out.append('|---|---|---|---|---|---|')
                for v in vs:
                    nod = {k: val for k, val in v.items() if k != 'dialects'}
                    out.append(f"| {v['name']} | `{v['voice']}` | "
                               f"{GENDER.get(v.get('gender'), '—')} | "
                               f"{'、'.join(v.get('dialects', []))} | {fmt_lang(nod)} | {fmt_feat(v)} |")
            else:
                out.append('| 音色 | voice 参数 | 性别 | 描述 | 适用场景 | 语种 | 特性 |')
                out.append('|---|---|---|---|---|---|---|')
                for v in vs:
                    scenes = '、'.join(v.get('scenarios', [])) or '—'
                    out.append(f"| {v['name']} | `{v['voice']}` | "
                               f"{GENDER.get(v.get('gender'), '—')} | "
                               f"{fmt_desc(v)} | {scenes} | {fmt_lang(v)} | {fmt_feat(v)} |")
    return '\n'.join(out) + '\n'


ARTIFACTS = (
    (SQL_OUT, build_sql),
    (MIGRATION_OUT, build_migration),
    (MD_OUT, build_md),
)


def check():
    """CI gate: every artifact on disk must be exactly what the JSON would produce."""
    stale = []
    for path, builder in ARTIFACTS:
        expected = builder()
        # `newline=''` compares the bytes as written: universal-newline translation would make this
        # gate pass on a CRLF checkout (or a hand-edited CRLF file) without noticing.
        actual = open(path, encoding='utf-8', newline='').read() if os.path.exists(path) else None
        if actual != expected:
            stale.append(os.path.relpath(path, HERE))
    if stale:
        print('Stale generated artifacts (run `python3 gen-tts-catalog.py`):')
        for path in stale:
            print(f'  {path}')
        return 1
    print('TTS catalog artifacts are up to date.')
    return 0


def main(argv):
    validate()
    if '--check' in argv:
        return check()
    for path, builder in ARTIFACTS:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        # Pin LF so the artifacts are byte-identical on every platform and the --check gate above
        # is meaningful in CI.
        open(path, 'w', encoding='utf-8', newline='\n').write(builder())
    total = sum(len(m['voices']) for m in DATA.values())
    print(f'wrote {len(ARTIFACTS)} artifacts; {total} voices')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
