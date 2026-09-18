"use client";

import {StreamLanguage} from "@codemirror/language";
import type {Extension} from "@codemirror/state";
import {json} from "@codemirror/lang-json";
import {yaml} from "@codemirror/lang-yaml";
import {xml} from "@codemirror/lang-xml";
import {markdown} from "@codemirror/lang-markdown";
import {properties} from "@codemirror/legacy-modes/mode/properties";
import {toml} from "@codemirror/legacy-modes/mode/toml";

export type LanguageId =
  | "yaml"
  | "json"
  | "xml"
  | "markdown"
  | "properties"
  | "toml"
  | "plain";

export function languageIdFromPath(path: string): LanguageId {
  const ext = path.split(".").pop()?.toLowerCase();
  switch (ext) {
    case "yaml":
    case "yml":
      return "yaml";
    case "json":
      return "json";
    case "xml":
      return "xml";
    case "md":
    case "markdown":
      return "markdown";
    case "properties":
    case "props":
      return "properties";
    case "toml":
      return "toml";
    default:
      return "plain";
  }
}

export function languageExtension(lang: LanguageId): Extension[] {
  switch (lang) {
    case "yaml":
      return [yaml()];
    case "json":
      return [json()];
    case "xml":
      return [xml()];
    case "markdown":
      return [markdown()];
    case "properties":
      return [StreamLanguage.define(properties)];
    case "toml":
      return [StreamLanguage.define(toml)];
    default:
      return [];
  }
}