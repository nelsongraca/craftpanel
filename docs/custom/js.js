class ColorSwatch extends HTMLElement {
  connectedCallback() {
    // You can even grab an attribute like 'hex'
    const hex = this.getAttribute('hex');
    this.style.backgroundColor = hex;
    this.style.display = 'inline-block';
    this.style.width = '14px';
    this.style.height = '14px';
    this.style.borderRadius = '3px';
  }
}

// Register the custom element
customElements.define('swat-ch', ColorSwatch);
