# scripts/train_baseline.py
import json
from pathlib import Path
from datasets import load_dataset
from transformers import (AutoTokenizer, AutoModelForTokenClassification,
                          DataCollatorForTokenClassification, TrainingArguments, Trainer)

ROOT = Path(__file__).resolve().parents[1]
train_p = str(ROOT/"data/kaggle_pii/pii_hf/train.jsonl")
dev_p   = str(ROOT/"data/kaggle_pii/pii_hf/dev.jsonl")
labels  = json.load(open(ROOT/"data/kaggle_pii/pii_hf/labels.json"))

label2id = {l:i for i,l in enumerate(labels)}
id2label = {i:l for l,i in label2id.items()}

ds = load_dataset("json", data_files={"train":train_p, "validation":dev_p})

model_name = "distilbert-base-uncased"
tok = AutoTokenizer.from_pretrained(model_name)
model = AutoModelForTokenClassification.from_pretrained(
    model_name, num_labels=len(labels), id2label=id2label, label2id=label2id
)

# --- char spans -> token BIO
def char_spans_to_bio(text, entities, enc):
    # simplistic aligner: mark tokens whose char range overlaps an entity
    encodings = enc(text, return_offsets_mapping=True, truncation=True, max_length=512)
    tags = ["O"] * len(encodings.offset_mapping)
    for ent in entities:
        s, e, lab = ent["start"], ent["end"], ent["label"]
        for i,(ts,te) in enumerate(encodings.offset_mapping):
            if ts==te: continue
            if te <= s or ts >= e: continue
            prefix = "B" if tags[i]=="O" else "I"
            tags[i] = f"{prefix}-{lab}"
    # map to ids, skip special tokens later via -100
    return encodings, tags

def to_features(ex):
    enc, tags = char_spans_to_bio(ex["text"], ex["entities"], tok)
    labels_ids = []
    for (ts,te), tag in zip(enc.offset_mapping, tags):
        if ts==te: labels_ids.append(-100)
        else:
            if tag=="O": labels_ids.append(-100 if te==0 else 0)  # will fix below if 'O' not in labels
            else:
                lab = tag.split("-",1)[1]
                labels_ids.append(label2id[lab])
    enc["labels"] = labels_ids
    enc.pop("offset_mapping")
    return enc

# If 'O' isn’t in labels.json, we don't predict it (only entity spans matter for strict F1)
ds_enc = ds.map(to_features, remove_columns=ds["train"].column_names)

collator = DataCollatorForTokenClassification(tok)
args = TrainingArguments(
    output_dir=str(ROOT/"models/pii_baseline"),
    learning_rate=5e-5, per_device_train_batch_size=16, per_device_eval_batch_size=16,
    num_train_epochs=3, evaluation_strategy="epoch", save_strategy="epoch",
    logging_steps=50
)
trainer = Trainer(model=model, args=args, train_dataset=ds_enc["train"], eval_dataset=ds_enc["validation"],
                  tokenizer=tok, data_collator=collator)
trainer.train()
trainer.save_model(str(ROOT/"models/pii_baseline"))
tok.save_pretrained(str(ROOT/"models/pii_baseline"))
