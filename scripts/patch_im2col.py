"""Pinned CPU im2col patch; retain the original path for unsupported layouts."""
import hashlib
import json

def transform(text):
    anchor='''    switch (dst->type) {
        case GGML_TYPE_F16:
            {
                ggml_compute_forward_im2col_f16(params, dst);'''
    if text.count(anchor)!=1: raise RuntimeError('Unexpected im2col dispatch')
    hook='''    if (mivoz_im2col_1d(dst, params->ith, params->nth,
            [](float value) { return GGML_CPU_FP32_TO_FP16(value); })) return;
'''
    return '#include "mivoz_im2col_1d.h"\n'+text.replace(anchor,hook+anchor,1)

def apply(root, check_only=False):
    folder=root/'external/qwen3-tts.cpp/ggml/src/ggml-cpu'
    entry=json.loads((root/'patches/im2col-manifest.json').read_text())
    path=folder/'ops.cpp';text=path.read_text(encoding='utf-8')
    digest=lambda value:hashlib.sha256(value.encode()).hexdigest()
    if digest(text)!=entry['patched']:
        if digest(text)!=entry['upstream']: raise RuntimeError('Unexpected im2col source')
        changed=transform(text)
        if digest(changed)!=entry['patched'] or check_only: raise RuntimeError('im2col patch not ready')
        path.write_text(changed,encoding='utf-8',newline='\n')
    header=(root/'patches/mivoz_im2col_1d.h').read_text(encoding='utf-8')
    target=folder/'mivoz_im2col_1d.h'
    if not target.is_file() or target.read_text(encoding='utf-8')!=header:
        if check_only: raise RuntimeError('im2col header not ready')
        target.write_text(header,encoding='utf-8',newline='\n')
