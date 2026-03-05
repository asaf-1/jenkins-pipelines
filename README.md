# Jenkins Central Pipelines

This repository contains the **central Jenkins pipelines** used to build and test multiple projects automatically.

## Purpose

The goal of this repository is to provide a **single, flexible Jenkins pipeline** that can be used across different repositories without requiring a separate `Jenkinsfile` in each project.

Instead of duplicating CI configuration in every repository, Jenkins can reference the pipeline defined here and apply it to multiple projects.

## What This Pipeline Supports

The pipeline is designed to automatically detect the project type and run the appropriate build or test process.

Currently supported project types include:

* **Node.js / JavaScript projects** (e.g. Playwright automation)
* **Python projects** (AI tools, scripts, testing frameworks)
* **Java / Maven projects** (including tools such as JMeter)
* **Static projects** (HTML tools, simple utilities)

The pipeline detects the project type by scanning for common configuration files such as:

* `package.json`
* `requirements.txt`
* `pom.xml`

Based on the detected type, Jenkins runs the appropriate build or test commands.

## Why This Approach

This repository allows Jenkins to:

* manage **multiple repositories from a single pipeline**
* avoid duplicating CI configuration
* support **projects written in different languages**
* make it easier to maintain and extend CI workflows

## Example Workflow

1. A project is pushed to GitHub
2. Jenkins scans the repositories
3. The central pipeline is triggered
4. Jenkins detects the project type
5. The appropriate build/test steps are executed

## Typical Projects Using This Pipeline

Examples of projects that may use this pipeline include:

* QA automation projects (Playwright)
* Python tools and scripts
* Load testing projects (JMeter)
* small development tools
* experimental or learning projects

## Notes

This repository is part of a **CI experimentation and automation portfolio** demonstrating Jenkins usage for multi-project environments.
